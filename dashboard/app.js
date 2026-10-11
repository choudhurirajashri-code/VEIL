/**
 * ═══════════════════════════════════════════════════════════════════
 * VEIL ADMIN DASHBOARD — CORE APPLICATION LOGIC (ZERO MOCK DATA)
 * 
 * Features:
 *   • Live 1-second real-time work timer ticker for in-campus employees
 *   • 6-Hour daily work requirement compliance engine
 *   • Daily, Weekly, and Monthly hours calculations
 *   • Passive WiFi RSSI campus node monitoring (Reception, Floor 1, Floor 2)
 *   • Interactive Grid, Data Table, and Floor Map views
 *   • Employee detail modal with timeline & telemetry
 *   • CSV Export & LocalStorage persistence
 *   • REST API sync with local backend server
 * ═══════════════════════════════════════════════════════════════════
 */

// ── Compliance Threshold Constants ──
const DAILY_COMPLIANCE_SECONDS = 6 * 3600; // 6 hours = 21,600 seconds
const WEEKLY_GOAL_SECONDS = 30 * 3600;    // 30 hours
const MONTHLY_GOAL_SECONDS = 132 * 3600;  // 132 hours

// ── Campus WiFi Nodes Reference ──
const CAMPUS_NODES = {
  RECEPTION: { id: 1, name: 'RECEPTION', ssid: 'OFFICE_RECEPTION', bssid: '58:BF:25:92:F3:41' },
  FLOOR_1:   { id: 2, name: 'FLOOR 1',   ssid: 'OFFICE_FLOOR1',    bssid: '88:13:BF:09:D6:55' },
  FLOOR_2:   { id: 3, name: 'FLOOR 2',   ssid: 'OFFICE_FLOOR2',    bssid: '8C:94:DF:6D:73:81' },
  OUTSIDE:   { id: 0, name: 'OUTSIDE',   ssid: '--',               bssid: '--' }
};

// ── Application State (Zero Mock Data) ──
let employees = [];
let currentFilter = 'all';
let currentSearch = '';
let currentDept = 'all';
let currentFloor = 'all';
let currentSort = 'hours-desc';
let activeView = 'grid'; // 'grid' | 'table' | 'floor'
let selectedEmployee = null;

// ═══════════════════════════════════════════════════════════════════
//  INITIALIZATION & STATE RECOVERY
// ═══════════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', async () => {
  // Purge any legacy mock data from old sessions completely
  localStorage.removeItem('veil_employees');

  await loadEmployeesFromStorage();
  initClock();
  initListeners();
  renderAll();

  // Start the 1-second real-time work timer
  setInterval(tickWorkTimers, 1000);

  // Poll local REST API if available
  checkBackendConnection();
  setInterval(checkBackendConnection, 5000);
});

async function loadEmployeesFromStorage() {
  // First attempt: fetch from server
  try {
    const res = await fetch('/api/employees', { cache: 'no-store' });
    if (res.ok) {
      employees = await res.json();
      saveEmployeesToStorage();
      return;
    }
  } catch (e) {
    // Backend offline, fallback to clean local storage
  }

  const cached = localStorage.getItem('veil_employees_clean');
  if (cached) {
    try {
      const parsed = JSON.parse(cached);
      // Filter out any stale mock records if any remain
      const mockNames = ['Swarnadeep Choudhury', 'Priya Sharma', 'Rahul Mehta', 'Sneha Kulkarni'];
      employees = parsed.filter(e => !mockNames.includes(e.name));
      saveEmployeesToStorage();
      return;
    } catch (e) {
      console.warn('Corrupt local cache:', e);
    }
  }

  employees = [];
  saveEmployeesToStorage();
}

function saveEmployeesToStorage() {
  localStorage.setItem('veil_employees_clean', JSON.stringify(employees));
}

// ═══════════════════════════════════════════════════════════════════
//  TIME & COMPLIANCE CALCULATIONS
// ═══════════════════════════════════════════════════════════════════

/**
 * Returns compliance status:
 *   - 'compliant':     todaySeconds >= 6 hours (21,600s)
 *   - 'in-progress':   todaySeconds < 6 hours AND employee is currently inside campus
 *   - 'non-compliant': todaySeconds < 6 hours AND employee is outside campus
 */
function getComplianceStatus(emp) {
  if (emp.todaySeconds >= DAILY_COMPLIANCE_SECONDS) {
    return 'compliant';
  }
  if (emp.isInside) {
    return 'in-progress';
  }
  return 'non-compliant';
}

function formatHours(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return `${h}h ${m.toString().padStart(2, '0')}m`;
}

function formatHoursDetailed(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return `${h}h ${m.toString().padStart(2, '0')}m ${s.toString().padStart(2, '0')}s`;
}

function getCompliancePercent(seconds) {
  return Math.min(100, Math.round((seconds / DAILY_COMPLIANCE_SECONDS) * 100));
}

// ═══════════════════════════════════════════════════════════════════
//  REAL-TIME 1-SECOND TIMER TICK
// ═══════════════════════════════════════════════════════════════════
function tickWorkTimers() {
  if (employees.length === 0) return;

  let stateChanged = false;
  employees.forEach(emp => {
    if (emp.isInside) {
      emp.todaySeconds += 1;
      emp.weekSeconds += 1;
      emp.monthSeconds += 1;
      stateChanged = true;
    }
  });

  if (stateChanged) {
    updateKPIs();
    refreshVisibleHours();
  }
}

function refreshVisibleHours() {
  employees.forEach(emp => {
    const hoursEl = document.querySelector(`[data-emp-hours="${emp.id}"]`);
    if (hoursEl) {
      hoursEl.innerHTML = `${formatHours(emp.todaySeconds)} ${emp.isInside ? '<span class="live-ticking-dot"></span>' : ''}`;
    }

    const fillEl = document.querySelector(`[data-emp-fill="${emp.id}"]`);
    if (fillEl) {
      const pct = getCompliancePercent(emp.todaySeconds);
      fillEl.style.width = `${pct}%`;
      fillEl.style.background = pct >= 100 ? 'linear-gradient(90deg, #10B981, #34D399)' :
                                 pct >= 60 ? 'linear-gradient(90deg, #F59E0B, #FBBF24)' :
                                 'linear-gradient(90deg, #EF4444, #F87171)';
    }

    const statusEl = document.querySelector(`[data-emp-badge="${emp.id}"]`);
    if (statusEl) {
      const status = getComplianceStatus(emp);
      statusEl.className = `compliance-badge ${status}`;
      statusEl.innerHTML = status === 'compliant' ? '✓ Compliant (6h+)' :
                           status === 'in-progress' ? '⏳ In Progress' : '⚠ Non-Compliant (&lt;6h)';
    }
  });

  if (selectedEmployee) {
    const liveSec = employees.find(e => e.id === selectedEmployee.id);
    if (liveSec) {
      const drawerTimerEl = document.getElementById('drawerLiveTodayTimer');
      if (drawerTimerEl) {
        drawerTimerEl.textContent = formatHoursDetailed(liveSec.todaySeconds);
      }
    }
  }
}

// ═══════════════════════════════════════════════════════════════════
//  RENDER VIEWS (GRID, TABLE, FLOOR MAP)
// ═══════════════════════════════════════════════════════════════════
function renderAll() {
  updateKPIs();
  updateFilterCounts();

  const filtered = getFilteredAndSortedEmployees();

  const emptyState = document.getElementById('emptyState');
  const gridContainer = document.getElementById('gridContainer');
  const tableContainer = document.getElementById('tableContainer');
  const floorMapContainer = document.getElementById('floorMapContainer');

  if (filtered.length === 0) {
    emptyState.style.display = 'flex';
    gridContainer.style.display = 'none';
    tableContainer.style.display = 'none';
    floorMapContainer.style.display = 'none';

    if (employees.length === 0) {
      emptyState.querySelector('h3').textContent = 'No Employees Enrolled';
      emptyState.querySelector('p').textContent = 
        'No mock data present. Real employees will appear here when client devices transmit live Wi-Fi beacons or when registered manually.';
      const resetBtn = document.getElementById('btnResetFilters');
      resetBtn.textContent = '+ Register First Employee';
      resetBtn.onclick = openAddEmployeeModal;
    } else {
      emptyState.querySelector('h3').textContent = 'No matching employees found';
      emptyState.querySelector('p').textContent = 'Try adjusting your search criteria or resetting the filters.';
      const resetBtn = document.getElementById('btnResetFilters');
      resetBtn.textContent = 'Reset Filters';
      resetBtn.onclick = resetAllFilters;
    }
    return;
  }

  emptyState.style.display = 'none';

  if (activeView === 'grid') {
    gridContainer.style.display = 'grid';
    tableContainer.style.display = 'none';
    floorMapContainer.style.display = 'none';
    renderGrid(filtered);
  } else if (activeView === 'table') {
    gridContainer.style.display = 'none';
    tableContainer.style.display = 'block';
    floorMapContainer.style.display = 'none';
    renderTable(filtered);
  } else if (activeView === 'floor') {
    gridContainer.style.display = 'none';
    tableContainer.style.display = 'none';
    floorMapContainer.style.display = 'block';
    renderFloorMap(filtered);
  }
}

function updateKPIs() {
  const total = employees.length;
  const inside = employees.filter(e => e.isInside).length;
  const compliant = employees.filter(e => getComplianceStatus(e) === 'compliant').length;
  const nonCompliant = employees.filter(e => getComplianceStatus(e) === 'non-compliant').length;
  const inProgress = employees.filter(e => getComplianceStatus(e) === 'in-progress').length;

  const totalTodaySec = employees.reduce((acc, e) => acc + e.todaySeconds, 0);
  const avgHours = total > 0 ? (totalTodaySec / total / 3600).toFixed(1) : '0.0';
  const totalWeekHours = Math.round(employees.reduce((acc, e) => acc + e.weekSeconds, 0) / 3600);

  const complianceRate = total > 0 ? Math.round((compliant / total) * 100) : 0;
  const insideRate = total > 0 ? Math.round((inside / total) * 100) : 0;

  // KPI 1: Headcount
  document.getElementById('kpiInsideCount').textContent = inside;
  document.getElementById('kpiTotalEmployees').textContent = total;
  document.getElementById('kpiInsideProgress').style.width = `${insideRate}%`;
  document.getElementById('kpiInsidePercent').textContent = `${insideRate}%`;

  // KPI 2: Compliant
  document.getElementById('kpiCompliantCount').textContent = compliant;
  document.getElementById('kpiCompliantProgress').style.width = `${complianceRate}%`;
  document.getElementById('kpiCompliantRate').textContent = `${complianceRate}%`;
  document.getElementById('mandateCompliancePercent').textContent = `${complianceRate}%`;

  // KPI 3: Non-compliant / Pending
  document.getElementById('kpiNonCompliantCount').textContent = nonCompliant;
  const nonCompRate = total > 0 ? Math.round((nonCompliant / total) * 100) : 0;
  document.getElementById('kpiNonCompliantProgress').style.width = `${nonCompRate}%`;
  document.getElementById('kpiPendingNotice').innerHTML = 
    total === 0 ? 'Waiting for employee enrollment' :
    `<strong>${inProgress}</strong> in progress • <strong>${nonCompliant}</strong> non-compliant`;

  // KPI 4: Avg Hours
  document.getElementById('kpiAvgHours').textContent = `${avgHours}h`;
  const avgProgress = Math.min(100, Math.round((parseFloat(avgHours) / 6.0) * 100));
  document.getElementById('kpiAvgHoursProgress').style.width = `${avgProgress}%`;
  document.getElementById('kpiTotalWeeklyHours').textContent = `${totalWeekHours}h`;

  // Topology node occupant counts
  document.getElementById('node1Occupants').textContent = employees.filter(e => e.currentZone === 'RECEPTION').length;
  document.getElementById('node2Occupants').textContent = employees.filter(e => e.currentZone === 'FLOOR_1').length;
  document.getElementById('node3Occupants').textContent = employees.filter(e => e.currentZone === 'FLOOR_2').length;
  document.getElementById('nodeOutsideOccupants').textContent = employees.filter(e => e.currentZone === 'OUTSIDE').length;
}

function updateFilterCounts() {
  document.getElementById('countFilterAll').textContent = employees.length;
  document.getElementById('countFilterCompliant').textContent = employees.filter(e => getComplianceStatus(e) === 'compliant').length;
  document.getElementById('countFilterInProgress').textContent = employees.filter(e => getComplianceStatus(e) === 'in-progress').length;
  document.getElementById('countFilterNonCompliant').textContent = employees.filter(e => getComplianceStatus(e) === 'non-compliant').length;
  document.getElementById('countFilterInside').textContent = employees.filter(e => e.isInside).length;
  document.getElementById('countFilterOutside').textContent = employees.filter(e => !e.isInside).length;
}

function getFilteredAndSortedEmployees() {
  return employees.filter(emp => {
    const status = getComplianceStatus(emp);
    if (currentFilter === 'compliant' && status !== 'compliant') return false;
    if (currentFilter === 'in-progress' && status !== 'in-progress') return false;
    if (currentFilter === 'non-compliant' && status !== 'non-compliant') return false;
    if (currentFilter === 'inside' && !emp.isInside) return false;
    if (currentFilter === 'outside' && emp.isInside) return false;

    if (currentDept !== 'all' && emp.dept !== currentDept) return false;
    if (currentFloor !== 'all' && emp.currentZone !== currentFloor) return false;

    if (currentSearch.trim() !== '') {
      const q = currentSearch.toLowerCase();
      const matchName = emp.name.toLowerCase().includes(q);
      const matchId = emp.id.toLowerCase().includes(q);
      const matchRole = emp.role.toLowerCase().includes(q);
      const matchZone = emp.currentZone.toLowerCase().includes(q);
      if (!matchName && !matchId && !matchRole && !matchZone) return false;
    }

    return true;
  }).sort((a, b) => {
    if (currentSort === 'hours-desc') return b.todaySeconds - a.todaySeconds;
    if (currentSort === 'hours-asc') return a.todaySeconds - b.todaySeconds;
    if (currentSort === 'name-asc') return a.name.localeCompare(b.name);
    if (currentSort === 'week-desc') return b.weekSeconds - a.weekSeconds;
    if (currentSort === 'month-desc') return b.monthSeconds - a.monthSeconds;
    if (currentSort === 'compliance') {
      const order = { 'non-compliant': 1, 'in-progress': 2, 'compliant': 3 };
      return order[getComplianceStatus(a)] - order[getComplianceStatus(b)];
    }
    return 0;
  });
}

function renderGrid(list) {
  const container = document.getElementById('gridContainer');
  container.innerHTML = '';

  list.forEach(emp => {
    const status = getComplianceStatus(emp);
    const pct = getCompliancePercent(emp.todaySeconds);
    const initials = emp.name.split(' ').map(n => n[0]).join('').substring(0, 2);

    const card = document.createElement('div');
    card.className = `emp-card ${status}`;
    card.onclick = () => openEmployeeDrawer(emp.id);

    card.innerHTML = `
      <div class="card-top-row">
        <div class="emp-avatar-group">
          <div class="emp-avatar" style="--avatar-c1: ${emp.avatarColors ? emp.avatarColors[0] : '#3B82F6'}; --avatar-c2: ${emp.avatarColors ? emp.avatarColors[1] : '#10B981'};">
            ${initials}
            <span class="emp-presence-indicator ${emp.isInside ? 'online' : 'offline'}"></span>
          </div>
          <div class="emp-meta">
            <span class="emp-name">${emp.name}</span>
            <span class="emp-role">${emp.role} • <span class="emp-dept-badge">${emp.dept}</span></span>
          </div>
        </div>
        <div class="compliance-badge ${status}" data-emp-badge="${emp.id}">
          ${status === 'compliant' ? '✓ Compliant (6h+)' : status === 'in-progress' ? '⏳ In Progress' : '⚠ Non-Compliant (<6h)'}
        </div>
      </div>

      <div class="card-zone-row">
        <div class="zone-tag">
          <span class="badge-dot ${emp.isInside ? 'green' : 'red'}"></span>
          <span>${emp.isInside ? `Zone: ${emp.currentZone.replace('_', ' ')}` : 'OUTSIDE CAMPUS'}</span>
        </div>
        <div class="zone-signal-dbm">
          ${emp.isInside ? `${emp.signalDbm} dBm • ${emp.nearestAp}` : 'Disconnected'}
        </div>
      </div>

      <div class="card-hours-section">
        <div class="hours-header-row">
          <span class="hours-label">TODAY'S WORK HOURS</span>
          <span class="hours-value-main" data-emp-hours="${emp.id}">
            ${formatHours(emp.todaySeconds)} ${emp.isInside ? '<span class="live-ticking-dot"></span>' : ''}
          </span>
        </div>

        <div class="hours-progress-track">
          <div class="hours-progress-bar" data-emp-fill="${emp.id}" style="width: ${pct}%;"></div>
          <div class="hours-goal-marker" title="6.0h Daily Rule"></div>
        </div>
      </div>

      <div class="card-secondary-hours">
        <div class="sec-hour-item">
          <span class="sec-hour-label">THIS WEEK</span>
          <span class="sec-hour-val">${formatHours(emp.weekSeconds)}</span>
        </div>
        <div class="sec-hour-item">
          <span class="sec-hour-label">THIS MONTH</span>
          <span class="sec-hour-val">${formatHours(emp.monthSeconds)}</span>
        </div>
      </div>
    `;

    container.appendChild(card);
  });
}

function renderTable(list) {
  const tbody = document.getElementById('tableBody');
  tbody.innerHTML = '';

  list.forEach(emp => {
    const status = getComplianceStatus(emp);
    const pct = getCompliancePercent(emp.todaySeconds);
    const initials = emp.name.split(' ').map(n => n[0]).join('').substring(0, 2);

    const tr = document.createElement('tr');
    tr.style.cursor = 'pointer';
    tr.onclick = () => openEmployeeDrawer(emp.id);

    tr.innerHTML = `
      <td>
        <div class="table-emp-cell">
          <div class="table-avatar" style="background: linear-gradient(135deg, ${emp.avatarColors ? emp.avatarColors[0] : '#3B82F6'}, ${emp.avatarColors ? emp.avatarColors[1] : '#10B981'});">
            ${initials}
          </div>
          <div>
            <div style="font-weight: 700;">${emp.name}</div>
            <div style="font-size: 0.7rem; color: var(--text-muted);">${emp.id} • ${emp.role}</div>
          </div>
        </div>
      </td>
      <td><span style="font-size: 0.75rem; font-weight: 600;">${emp.dept}</span></td>
      <td>
        <span class="badge-dot ${emp.isInside ? 'green' : 'red'}"></span>
        <span style="font-weight: 600;">${emp.isInside ? emp.currentZone.replace('_', ' ') : 'OUTSIDE'}</span>
        <div style="font-size: 0.68rem; color: var(--text-muted);">${emp.isInside ? `${emp.signalDbm} dBm` : 'No signal'}</div>
      </td>
      <td>
        <strong data-emp-hours="${emp.id}">${formatHours(emp.todaySeconds)}</strong>
        ${emp.isInside ? '<span class="live-ticking-dot" style="margin-left: 4px;"></span>' : ''}
      </td>
      <td style="min-width: 140px;">
        <div style="font-size: 0.7rem; margin-bottom: 3px; display: flex; justify-content: space-between;">
          <span>${pct}% of 6h</span>
        </div>
        <div class="hours-progress-track">
          <div class="hours-progress-bar" data-emp-fill="${emp.id}" style="width: ${pct}%;"></div>
        </div>
      </td>
      <td><strong>${formatHours(emp.weekSeconds)}</strong></td>
      <td><strong>${formatHours(emp.monthSeconds)}</strong></td>
      <td>
        <span class="compliance-badge ${status}">
          ${status === 'compliant' ? '✓ Compliant' : status === 'in-progress' ? '⏳ In Progress' : '⚠ Non-Compliant'}
        </span>
      </td>
      <td>
        <button class="btn-ghost" style="padding: 4px 8px; font-size: 0.72rem;" onclick="event.stopPropagation(); openEmployeeDrawer('${emp.id}')">
          Inspect
        </button>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

function renderFloorMap(list) {
  const zones = {
    RECEPTION: document.getElementById('zoneReceptionList'),
    FLOOR_1:   document.getElementById('zoneFloor1List'),
    FLOOR_2:   document.getElementById('zoneFloor2List'),
    OUTSIDE:   document.getElementById('zoneOutsideList')
  };

  Object.values(zones).forEach(el => el.innerHTML = '');

  const counts = { RECEPTION: 0, FLOOR_1: 0, FLOOR_2: 0, OUTSIDE: 0 };

  list.forEach(emp => {
    const targetZone = emp.currentZone in zones ? emp.currentZone : 'OUTSIDE';
    counts[targetZone]++;

    const status = getComplianceStatus(emp);
    const chip = document.createElement('div');
    chip.className = 'zone-emp-chip';
    chip.onclick = () => openEmployeeDrawer(emp.id);

    chip.innerHTML = `
      <div style="display: flex; align-items: center; gap: 8px;">
        <span class="badge-dot ${emp.isInside ? 'green' : 'red'}"></span>
        <div>
          <div style="font-weight: 700; font-size: 0.8rem;">${emp.name}</div>
          <div style="font-size: 0.68rem; color: var(--text-muted);">${emp.role}</div>
        </div>
      </div>
      <div style="text-align: right;">
        <div style="font-weight: 700; font-size: 0.8rem;">${formatHours(emp.todaySeconds)}</div>
        <span class="compliance-badge ${status}" style="font-size: 0.65rem; padding: 1px 5px;">
          ${status === 'compliant' ? '6h+ ✓' : status === 'in-progress' ? 'Prog' : '<6h'}
        </span>
      </div>
    `;

    zones[targetZone].appendChild(chip);
  });

  document.getElementById('zoneCountReception').textContent = counts.RECEPTION;
  document.getElementById('zoneCountFloor1').textContent = counts.FLOOR_1;
  document.getElementById('zoneCountFloor2').textContent = counts.FLOOR_2;
  document.getElementById('zoneCountOutside').textContent = counts.OUTSIDE;
}

// ═══════════════════════════════════════════════════════════════════
//  EMPLOYEE DETAIL DRAWER
// ═══════════════════════════════════════════════════════════════════
function openEmployeeDrawer(empId) {
  const emp = employees.find(e => e.id === empId);
  if (!emp) return;
  selectedEmployee = emp;

  document.getElementById('drawerName').textContent = emp.name;
  document.getElementById('drawerRoleDept').textContent = `${emp.role} • ${emp.dept} (${emp.id})`;

  const content = document.getElementById('drawerContent');
  const status = getComplianceStatus(emp);
  const pct = getCompliancePercent(emp.todaySeconds);
  const initials = emp.name.split(' ').map(n => n[0]).join('').substring(0, 2);

  content.innerHTML = `
    <!-- Top Stats Banner -->
    <div style="display: flex; align-items: center; gap: 16px; background: var(--bg-tertiary); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle);">
      <div class="emp-avatar" style="width: 54px; height: 54px; font-size: 1.3rem; --avatar-c1: ${emp.avatarColors ? emp.avatarColors[0] : '#3B82F6'}; --avatar-c2: ${emp.avatarColors ? emp.avatarColors[1] : '#10B981'};">
        ${initials}
      </div>
      <div style="flex-grow: 1;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
          <span style="font-size: 0.75rem; color: var(--text-muted); font-weight: 600;">TODAY'S WORKING TIME</span>
          <span class="compliance-badge ${status}">${status === 'compliant' ? '✓ 6H Rule Met' : status === 'in-progress' ? '⏳ On Track' : '⚠ Non-Compliant'}</span>
        </div>
        <div id="drawerLiveTodayTimer" style="font-family: var(--font-heading); font-size: 1.6rem; font-weight: 800; color: var(--text-primary);">
          ${formatHoursDetailed(emp.todaySeconds)}
        </div>
      </div>
    </div>

    <!-- 6-Hour Goal Gauge Card -->
    <div style="background: var(--bg-tertiary); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle);">
      <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
        <span style="font-size: 0.78rem; font-weight: 600;">6.0H DAILY RULE PROGRESS</span>
        <span style="font-size: 0.78rem; font-weight: 700; color: ${pct >= 100 ? 'var(--accent-green)' : 'var(--accent-amber)'};">${pct}%</span>
      </div>
      <div class="hours-progress-track" style="height: 10px;">
        <div class="hours-progress-bar" style="width: ${pct}%; background: ${pct >= 100 ? 'var(--accent-green)' : 'var(--accent-amber)'};"></div>
      </div>
      <div style="font-size: 0.72rem; color: var(--text-muted); margin-top: 6px;">
        ${emp.todaySeconds >= DAILY_COMPLIANCE_SECONDS ? 
          '✅ Requirement satisfied. Overtime active.' : 
          `⏳ Needs ${(6 - emp.todaySeconds / 3600).toFixed(1)} more hours on-campus to achieve compliance.`}
      </div>
    </div>

    <!-- Aggregate Hours (Week & Month) -->
    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
      <div style="background: var(--bg-tertiary); padding: 14px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle);">
        <div style="font-size: 0.7rem; color: var(--text-muted); font-weight: 600;">THIS WEEK'S TOTAL</div>
        <div style="font-family: var(--font-heading); font-size: 1.3rem; font-weight: 800; color: var(--text-primary); margin: 4px 0;">
          ${formatHours(emp.weekSeconds)}
        </div>
        <div style="font-size: 0.7rem; color: var(--accent-blue);">Target: 30.0 hrs</div>
      </div>
      <div style="background: var(--bg-tertiary); padding: 14px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle);">
        <div style="font-size: 0.7rem; color: var(--text-muted); font-weight: 600;">THIS MONTH'S TOTAL</div>
        <div style="font-family: var(--font-heading); font-size: 1.3rem; font-weight: 800; color: var(--text-primary); margin: 4px 0;">
          ${formatHours(emp.monthSeconds)}
        </div>
        <div style="font-size: 0.7rem; color: var(--accent-purple);">Target: 132.0 hrs</div>
      </div>
    </div>

    <!-- Active Hardware & Device Telemetry -->
    <div style="background: var(--bg-tertiary); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle);">
      <div style="font-size: 0.78rem; font-weight: 700; margin-bottom: 10px; display: flex; align-items: center; gap: 6px;">
        <span class="badge-dot blue"></span> HARDWARE TELEMETRY (PURE WIFI RSSI)
      </div>
      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 0.75rem;">
        <div><span style="color: var(--text-muted);">Current Zone:</span> <strong>${emp.currentZone}</strong></div>
        <div><span style="color: var(--text-muted);">Beacon Node:</span> <strong>${emp.nearestAp}</strong></div>
        <div><span style="color: var(--text-muted);">Signal Strength:</span> <strong>${emp.signalDbm} dBm</strong></div>
        <div><span style="color: var(--text-muted);">BSSID MAC:</span> <code>${emp.bssid}</code></div>
        <div><span style="color: var(--text-muted);">Device Client:</span> <strong>${emp.device}</strong></div>
        <div><span style="color: var(--text-muted);">Device Battery:</span> <strong>${emp.battery}%</strong></div>
      </div>
    </div>

    <!-- Admin Presence Override -->
    <div style="display: flex; gap: 10px;">
      <button class="btn-ghost" style="flex: 1;" onclick="manualTogglePresence('${emp.id}')">
        ${emp.isInside ? '🚪 Check-out (Step Outside)' : '🏢 Check-in (Enter Campus)'}
      </button>
      <button class="btn-ghost" style="flex: 1;" onclick="manualHopFloor('${emp.id}')">
        📍 Reassign Floor
      </button>
    </div>

    <!-- Today's Punch & Move Timeline -->
    <div>
      <div style="font-size: 0.8rem; font-weight: 700; margin-bottom: 12px;">TODAY'S ACTIVITY LOG</div>
      <div style="display: flex; flex-direction: column; gap: 10px;">
        ${(emp.history && emp.history.length > 0) ? emp.history.map(item => `
          <div style="display: flex; align-items: flex-start; gap: 12px; font-size: 0.78rem; border-left: 2px solid var(--accent-blue); padding-left: 12px; position: relative;">
            <div style="font-weight: 700; color: var(--accent-cyan); min-width: 65px;">${item.time}</div>
            <div>
              <div style="font-weight: 600;">${item.event}</div>
              <div style="font-size: 0.7rem; color: var(--text-muted);">Zone tag: ${item.zone}</div>
            </div>
          </div>
        `).join('') : '<div style="font-size: 0.75rem; color: var(--text-muted);">No activity events recorded yet today.</div>'}
      </div>
    </div>
  `;

  document.getElementById('drawerOverlay').classList.add('open');
  document.getElementById('employeeDrawer').classList.add('open');
}

function closeEmployeeDrawer() {
  document.getElementById('drawerOverlay').classList.remove('open');
  document.getElementById('employeeDrawer').classList.remove('open');
  selectedEmployee = null;
}

// ═══════════════════════════════════════════════════════════════════
//  ADMIN OVERRIDES & DATA MANAGEMENT
// ═══════════════════════════════════════════════════════════════════
function manualTogglePresence(empId) {
  const emp = employees.find(e => e.id === empId);
  if (!emp) return;

  emp.isInside = !emp.isInside;
  const nowTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  if (!emp.history) emp.history = [];

  if (emp.isInside) {
    emp.currentZone = 'FLOOR_1';
    emp.nearestAp = 'OFFICE_FLOOR1';
    emp.bssid = '88:13:BF:09:D6:55';
    emp.signalDbm = -50;
    emp.history.unshift({ time: nowTime, event: 'Manual check-in at Floor 1', zone: 'FLOOR_1' });
    showToast(`Checked in ${emp.name} at Floor 1`, 'success');
  } else {
    emp.currentZone = 'OUTSIDE';
    emp.nearestAp = '--';
    emp.bssid = '--';
    emp.signalDbm = -100;
    emp.history.unshift({ time: nowTime, event: 'Manual check-out to Outside', zone: 'OUTSIDE' });
    showToast(`${emp.name} is now outside campus`, 'warning');
  }

  saveEmployeesToStorage();
  renderAll();
  if (selectedEmployee && selectedEmployee.id === empId) {
    openEmployeeDrawer(empId);
  }
}

function manualHopFloor(empId) {
  const emp = employees.find(e => e.id === empId);
  if (!emp) return;

  const cycle = ['RECEPTION', 'FLOOR_1', 'FLOOR_2'];
  let nextIdx = (cycle.indexOf(emp.currentZone) + 1) % cycle.length;
  const nextZone = cycle[nextIdx];
  const node = CAMPUS_NODES[nextZone];

  emp.isInside = true;
  emp.currentZone = nextZone;
  emp.nearestAp = node.ssid;
  emp.bssid = node.bssid;
  emp.signalDbm = -50;

  if (!emp.history) emp.history = [];
  const nowTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  emp.history.unshift({ time: nowTime, event: `Zone updated to ${node.name}`, zone: nextZone });

  showToast(`Assigned ${emp.name} to ${node.name}`, 'info');
  saveEmployeesToStorage();
  renderAll();
  if (selectedEmployee && selectedEmployee.id === empId) {
    openEmployeeDrawer(empId);
  }
}

async function syncWithServer() {
  try {
    const res = await fetch('/api/employees', { cache: 'no-store' });
    if (res.ok) {
      employees = await res.json();
      saveEmployeesToStorage();
      renderAll();
      showToast(`Synchronized with VEIL Server (${employees.length} records)`, 'info');
    }
  } catch (err) {
    showToast('VEIL Server is unreachable', 'error');
  }
}

async function wipeAllStoreData() {
  if (!confirm('Are you sure you want to permanently clear all employee records?')) return;
  employees = [];
  saveEmployeesToStorage();
  localStorage.removeItem('veil_employees');
  localStorage.removeItem('veil_employees_clean');
  try {
    await fetch('/api/reset', { method: 'POST' });
  } catch (err) {}
  renderAll();
  showToast('All employee attendance records cleared completely (0 records).', 'warning');
}

// ═══════════════════════════════════════════════════════════════════
//  EXPORT TO CSV
// ═══════════════════════════════════════════════════════════════════
function exportAttendanceCsv() {
  if (employees.length === 0) {
    showToast('No employee attendance records to export.', 'warning');
    return;
  }

  const headers = [
    'Employee ID',
    'Full Name',
    'Department',
    'Role',
    'Campus Zone',
    'Is Inside Campus',
    "Today's Hours (HH:MM)",
    "Today's Seconds",
    '6H Rule Compliance Status',
    "Weekly Total (HH:MM)",
    "Monthly Total (HH:MM)",
    'Nearest AP SSID',
    'WiFi BSSID',
    'RSSI dBm',
    'Client Device Platform'
  ];

  const rows = employees.map(e => [
    `"${e.id}"`,
    `"${e.name}"`,
    `"${e.dept}"`,
    `"${e.role}"`,
    `"${e.currentZone}"`,
    e.isInside ? 'YES' : 'NO',
    `"${formatHours(e.todaySeconds)}"`,
    e.todaySeconds,
    `"${getComplianceStatus(e).toUpperCase()}"`,
    `"${formatHours(e.weekSeconds)}"`,
    `"${formatHours(e.monthSeconds)}"`,
    `"${e.nearestAp}"`,
    `"${e.bssid}"`,
    e.signalDbm,
    `"${e.device}"`
  ]);

  const csvContent = 'data:text/csv;charset=utf-8,' + [headers.join(','), ...rows.map(r => r.join(','))].join('\n');
  const encodedUri = encodeURI(csvContent);
  const link = document.createElement('a');
  link.setAttribute('href', encodedUri);
  link.setAttribute('download', `VEIL_Attendance_Compliance_Report_${new Date().toISOString().slice(0, 10)}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);

  showToast('Attendance report exported as CSV', 'success');
}

// ═══════════════════════════════════════════════════════════════════
//  ADD EMPLOYEE MODAL
// ═══════════════════════════════════════════════════════════════════
function openAddEmployeeModal() {
  document.getElementById('modalAddOverlay').classList.add('open');
  document.getElementById('empIdInput').value = `EMP-${100 + employees.length + 1}`;
  document.getElementById('empNameInput').focus();
}

function closeAddEmployeeModal() {
  document.getElementById('modalAddOverlay').classList.remove('open');
  document.getElementById('addEmployeeForm').reset();
}

async function handleAddEmployee(e) {
  e.preventDefault();
  const id = document.getElementById('empIdInput').value.trim();
  const name = document.getElementById('empNameInput').value.trim();
  const role = document.getElementById('empRoleInput').value.trim();
  const dept = document.getElementById('empDeptInput').value;
  const initialZone = document.getElementById('empInitialZone').value;
  const device = document.getElementById('empDeviceInput').value;

  const colors = [
    ['#3B82F6', '#10B981'],
    ['#8B5CF6', '#EC4899'],
    ['#06B6D4', '#3B82F6'],
    ['#F59E0B', '#EF4444'],
    ['#10B981', '#3B82F6']
  ];
  const chosenColor = colors[Math.floor(Math.random() * colors.length)];
  const isInside = initialZone !== 'OUTSIDE';
  const node = CAMPUS_NODES[initialZone];

  const newEmp = {
    id,
    name,
    role,
    dept,
    avatarColors: chosenColor,
    isInside,
    currentZone: initialZone,
    nearestAp: node ? node.ssid : '--',
    bssid: node ? node.bssid : '--',
    signalDbm: isInside ? -52 : -100,
    device,
    battery: 100,
    todaySeconds: 0,
    weekSeconds: 0,
    monthSeconds: 0,
    history: [
      { time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), event: 'Employee Enrolled into VEIL', zone: initialZone }
    ]
  };

  employees.unshift(newEmp);
  saveEmployeesToStorage();

  // Also post to backend if active
  try {
    await fetch('/api/heartbeat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        employeeId: newEmp.id,
        employeeName: newEmp.name,
        isInsideCampus: newEmp.isInside,
        currentZone: newEmp.currentZone,
        nearestAp: newEmp.nearestAp,
        bssid: newEmp.bssid,
        signalDbm: newEmp.signalDbm,
        device: newEmp.device
      })
    });
  } catch (err) {
    // Backend offline, stored locally
  }

  closeAddEmployeeModal();
  renderAll();
  showToast(`Registered new employee ${name} (${id})`, 'success');
}

// ═══════════════════════════════════════════════════════════════════
//  CLOCK & THEME HANDLERS
// ═══════════════════════════════════════════════════════════════════
function initClock() {
  const updateClock = () => {
    const now = new Date();
    document.getElementById('liveClockDate').textContent = now.toLocaleDateString('en-US', {
      weekday: 'short', month: 'short', day: 'numeric', year: 'numeric'
    });
    document.getElementById('liveClockTime').textContent = now.toLocaleTimeString('en-US', {
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
  };
  updateClock();
  setInterval(updateClock, 1000);
}

function toggleTheme() {
  document.body.classList.toggle('light-theme');
  const isLight = document.body.classList.contains('light-theme');
  localStorage.setItem('veil_theme', isLight ? 'light' : 'dark');
}

// ═══════════════════════════════════════════════════════════════════
//  BACKEND REST API INTEGRATION
// ═══════════════════════════════════════════════════════════════════
async function checkBackendConnection() {
  const dot = document.getElementById('apiStatusDot');
  const text = document.getElementById('apiStatusText');
  try {
    const res = await fetch('/api/stats', { cache: 'no-store' });
    if (res.ok) {
      dot.style.background = '#10B981';
      dot.style.boxShadow = '0 0 8px #10B981';
      text.textContent = 'REST API Connected';

      // Sync employees from backend
      const empRes = await fetch('/api/employees', { cache: 'no-store' });
      if (empRes.ok) {
        const serverEmps = await empRes.json();
        if (JSON.stringify(serverEmps) !== JSON.stringify(employees)) {
          employees = serverEmps;
          saveEmployeesToStorage();
          renderAll();
        }
      }
    } else {
      setOfflineStatus();
    }
  } catch (err) {
    setOfflineStatus();
  }

  function setOfflineStatus() {
    dot.style.background = '#3B82F6';
    dot.style.boxShadow = '0 0 8px #3B82F6';
    text.textContent = 'Local Engine (Standalone)';
  }
}

// ═══════════════════════════════════════════════════════════════════
//  UI LISTENERS
// ═══════════════════════════════════════════════════════════════════
function resetAllFilters() {
  currentFilter = 'all';
  currentSearch = '';
  currentDept = 'all';
  currentFloor = 'all';
  document.getElementById('searchInput').value = '';
  document.getElementById('departmentFilter').value = 'all';
  document.getElementById('floorFilter').value = 'all';
  document.querySelectorAll('.filter-tab').forEach(t => t.classList.toggle('active', t.dataset.filter === 'all'));
  renderAll();
}

function initListeners() {
  document.getElementById('btnToggleTheme').addEventListener('click', toggleTheme);
  if (localStorage.getItem('veil_theme') === 'light') {
    document.body.classList.add('light-theme');
  }

  document.querySelectorAll('.filter-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentFilter = tab.dataset.filter;
      renderAll();
    });
  });

  const searchInput = document.getElementById('searchInput');
  const btnClearSearch = document.getElementById('btnClearSearch');
  searchInput.addEventListener('input', (e) => {
    currentSearch = e.target.value;
    btnClearSearch.style.display = currentSearch ? 'block' : 'none';
    renderAll();
  });
  btnClearSearch.addEventListener('click', () => {
    searchInput.value = '';
    currentSearch = '';
    btnClearSearch.style.display = 'none';
    renderAll();
  });

  document.getElementById('departmentFilter').addEventListener('change', (e) => {
    currentDept = e.target.value;
    renderAll();
  });
  document.getElementById('floorFilter').addEventListener('change', (e) => {
    currentFloor = e.target.value;
    renderAll();
  });
  document.getElementById('sortDropdown').addEventListener('change', (e) => {
    currentSort = e.target.value;
    renderAll();
  });

  const btnGrid = document.getElementById('btnViewGrid');
  const btnTable = document.getElementById('btnViewTable');
  const btnFloor = document.getElementById('btnViewFloor');

  btnGrid.addEventListener('click', () => {
    btnGrid.classList.add('active');
    btnTable.classList.remove('active');
    btnFloor.classList.remove('active');
    activeView = 'grid';
    renderAll();
  });

  btnTable.addEventListener('click', () => {
    btnTable.classList.add('active');
    btnGrid.classList.remove('active');
    btnFloor.classList.remove('active');
    activeView = 'table';
    renderAll();
  });

  btnFloor.addEventListener('click', () => {
    btnFloor.classList.add('active');
    btnGrid.classList.remove('active');
    btnTable.classList.remove('active');
    activeView = 'floor';
    renderAll();
  });

  const btnSync = document.getElementById('btnSyncServer');
  if (btnSync) btnSync.addEventListener('click', syncWithServer);

  const btnReset = document.getElementById('btnResetStore');
  if (btnReset) btnReset.addEventListener('click', wipeAllStoreData);
  document.getElementById('btnExportCsv').addEventListener('click', exportAttendanceCsv);
  document.getElementById('btnAddEmployee').addEventListener('click', openAddEmployeeModal);
  document.getElementById('btnCloseAddModal').addEventListener('click', closeAddEmployeeModal);
  document.getElementById('btnCancelAdd').addEventListener('click', closeAddEmployeeModal);
  document.getElementById('addEmployeeForm').addEventListener('submit', handleAddEmployee);

  document.getElementById('btnCloseDrawer').addEventListener('click', closeEmployeeDrawer);
  document.getElementById('drawerOverlay').addEventListener('click', closeEmployeeDrawer);

  document.getElementById('btnResetFilters').addEventListener('click', resetAllFilters);
}

// ── TOAST NOTIFICATIONS ──
function showToast(message, type = 'info') {
  const container = document.getElementById('toastContainer');
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `<span>${message}</span>`;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 250);
  }, 3200);
}
