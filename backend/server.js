const express = require('express');
const cors = require('cors');
const bodyParser = require('body-parser');
const path = require('path');

const app = express();
const PORT = 3000;

// Middleware
app.use(cors()); // Allow Websites to talk to us
app.use(bodyParser.json({ limit: '50mb' })); // ⚠️ Important: Allow Big Proofs (50MB limit)
app.use(express.static('public')); // NEW LINE fronted file 

// 🧠 IN-MEMORY DATABASE (Temporary Storage)
// Format: { "session_id": { status: "pending", proof: null } }
const sessions = new Map();

console.log("🦁 ZK Relay Server Starting...");

// 1. START SESSION (Website calls this to get a QR Code)
app.get('/api/start-session', (req, res) => {
    const sessionId = require('crypto').randomUUID();
    sessions.set(sessionId, { status: 'pending', proof: null });
    
    console.log(`🆕 Session Created: ${sessionId}`);
    res.json({ session_id: sessionId });
});

// 2. UPLOAD PROOF (Android App calls this)
app.post('/api/upload-proof', (req, res) => {
    const { session_id, proof_data } = req.body;

    if (!sessions.has(session_id)) {
        return res.status(404).json({ error: "Session Expired or Invalid" });
    }

    // Save the proof
    sessions.set(session_id, { status: 'completed', proof: proof_data });
    
    console.log(`✅ Proof Received for: ${session_id}`);
    res.json({ success: true });
});

// 3. CHECK STATUS (Website polls this to see if User scanned)
app.get('/api/poll-status/:session_id', (req, res) => {
    const sessionId = req.params.session_id;
    const session = sessions.get(sessionId);

    if (!session) {
        return res.status(404).json({ error: "Session Not Found" });
    }

    // Return status and proof (if ready)
    res.json(session);
});

// Start Server
app.listen(PORT, () => {
    console.log(`🚀 Relay Server running on http://localhost:${PORT}`);
});