/**
 * ═══════════════════════════════════════════════════════════════════
 * VEIL ADMIN DASHBOARD — STANDALONE HTTP & REST API SERVER
 * Built with pure Node.js standard modules (zero external npm dependencies)
 * 
 * Endpoints:
 *   • GET  /                       -> Serves index.html
 *   • GET  /style.css, /app.js     -> Serves static assets
 *   • GET  /api/employees          -> Returns current employee attendance records
 *   • GET  /api/stats              -> Returns aggregate compliance & presence KPIs
 *   • POST /api/heartbeat          -> Receives live ping from Android / Windows client
 *   • POST /api/employees          -> Enrolls a new employee
 * ═══════════════════════════════════════════════════════════════════
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const DATA_FILE = path.join(__dirname, 'attendance_store.json');

// Memory store initialized from disk or empty
let employeeStore = [];

function loadData() {
  if (fs.existsSync(DATA_FILE)) {
    try {
      employeeStore = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
      console.log(`[VEIL Server] Loaded ${employeeStore.length} employee records from disk.`);
      return;
    } catch (e) {
      console.warn('[VEIL Server] Error reading storage file, initializing empty store:', e);
    }
  }
  employeeStore = [];
  saveData();
}

function saveData() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(employeeStore, null, 2), 'utf8');
  } catch (e) {
    console.error('[VEIL Server] Failed to save data to disk:', e);
  }
}

loadData();

// MIME Types mapping
const MIME_TYPES = {
  '.html': 'text/html; charset=UTF-8',
  '.css':  'text/css; charset=UTF-8',
  '.js':   'application/javascript; charset=UTF-8',
  '.json': 'application/json; charset=UTF-8',
  '.png':  'image/png',
  '.svg':  'image/svg+xml'
};

const server = http.createServer((req, res) => {
  // CORS Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname;

  // ── REST API ROUTES ──
  if (pathname === '/api/employees' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(employeeStore));
    return;
  }

  if (pathname === '/api/stats' && req.method === 'GET') {
    const total = employeeStore.length;
    const inside = employeeStore.filter(e => e.isInside).length;
    const compliant = employeeStore.filter(e => e.todaySeconds >= 6 * 3600).length;
    const stats = {
      totalEmployees: total,
      insideCount: inside,
      compliantCount: compliant,
      complianceRate: total > 0 ? Math.round((compliant / total) * 100) : 0,
      timestamp: new Date().toISOString()
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(stats));
    return;
  }

  // Enroll a new employee via REST
  if (pathname === '/api/employees' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const newEmp = JSON.parse(body);
        if (!newEmp.id) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: false, error: 'Employee ID is required' }));
          return;
        }
        const existingIdx = employeeStore.findIndex(e => e.id === newEmp.id);
        if (existingIdx >= 0) {
          employeeStore[existingIdx] = { ...employeeStore[existingIdx], ...newEmp };
        } else {
          employeeStore.push(newEmp);
        }
        saveData();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, employee: newEmp }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Invalid JSON payload' }));
      }
    });
    return;
  }

  // Reset / wipe all employee data completely
  if (pathname === '/api/reset' && req.method === 'POST') {
    employeeStore = [];
    saveData();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true, message: 'All employee attendance records wiped clean' }));
    return;
  }

  // Live Heartbeat receiver from Android app or Windows service
  if (pathname === '/api/heartbeat' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const payload = JSON.parse(body);
        const empId = payload.employeeId || ('DEV-' + Date.now().toString().slice(-4));
        let emp = employeeStore.find(e => e.id === empId);

        const now = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        if (!emp) {
          emp = {
            id: empId,
            name: payload.employeeName || (`User ${empId}`),
            role: payload.role || 'Staff',
            dept: payload.dept || 'General',
            avatarColors: ['#3B82F6', '#10B981'],
            isInside: !!payload.isInsideCampus,
            currentZone: payload.currentZone || (payload.isInsideCampus ? 'CAMPUS' : 'OUTSIDE'),
            nearestAp: payload.nearestAp || '--',
            bssid: payload.bssid || '--',
            signalDbm: payload.signalDbm || -50,
            device: payload.device || 'Mobile Client',
            battery: payload.battery || 100,
            todaySeconds: 0,
            weekSeconds: 0,
            monthSeconds: 0,
            history: [{ time: now, event: 'First ping received', zone: payload.currentZone }]
          };
          employeeStore.push(emp);
        } else {
          const oldZone = emp.currentZone;
          emp.isInside = !!payload.isInsideCampus;
          emp.currentZone = payload.currentZone || (payload.isInsideCampus ? emp.currentZone : 'OUTSIDE');
          emp.nearestAp = payload.nearestAp || emp.nearestAp;
          emp.bssid = payload.bssid || emp.bssid;
          emp.signalDbm = payload.signalDbm || emp.signalDbm;

          if (oldZone !== emp.currentZone) {
            emp.history.unshift({ time: now, event: `Zone updated to ${emp.currentZone}`, zone: emp.currentZone });
          }
        }

        saveData();
        console.log(`[VEIL Heartbeat] ${emp.name} (${emp.id}): ${emp.isInside ? 'INSIDE' : 'OUTSIDE'} [${emp.currentZone}] ${emp.signalDbm}dBm`);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Heartbeat logged', employee: emp }));
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'Invalid JSON payload' }));
      }
    });
    return;
  }

  // ── STATIC FILE SERVING ──
  let filePath = path.join(__dirname, pathname === '/' ? 'index.html' : pathname);
  const ext = path.extname(filePath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';

  fs.readFile(filePath, (err, content) => {
    if (err) {
      if (err.code === 'ENOENT') {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('404 Not Found');
      } else {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end(`500 Server Error: ${err.code}`);
      }
    } else {
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content);
    }
  });
});

server.listen(PORT, () => {
  console.log(`════════════════════════════════════════════════════════════`);
  console.log(` VEIL Campus Attendance & Compliance Server Running!`);
  console.log(` • Dashboard URL: http://localhost:${PORT}`);
  console.log(` • REST Heartbeat API: http://localhost:${PORT}/api/heartbeat`);
  console.log(`════════════════════════════════════════════════════════════`);
});
