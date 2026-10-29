// Optional phone capability, owned by Electron. Never exports the desktop API secret.
const { app, dialog, clipboard, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes } = require('node:crypto');
const { startMobileBridge } = require('./mobile-bridge.cjs');

function createMobileConnection(desktopUrl, desktopSecret) {
  let server;
  let token;
  const tokenFile = path.join(app.getPath('userData'), 'mobile-pairing.bin');
  async function stop() {
    if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); server = null; }
    token = null;
    if (fs.existsSync(tokenFile)) fs.unlinkSync(tokenFile);
  }
  async function show() {
    try {
      if (!safeStorage.isEncryptionAvailable()) throw new Error('Secure credential storage is unavailable.');
      if (!server) {
        token = fs.existsSync(tokenFile) ? safeStorage.decryptString(fs.readFileSync(tokenFile)) : randomBytes(32).toString('hex');
        if (!fs.existsSync(tokenFile)) fs.writeFileSync(tokenFile, safeStorage.encryptString(token), {mode:0o600});
        server = await startMobileBridge({desktopUrl, desktopSecret, token});
      }
      const result = await dialog.showMessageBox({type:'info', title:'Connect your Android phone',
        message:'Phone connection is running', detail:'1. Install Tailscale on this PC and your phone; sign in to the same account.\n2. Run: tailscale serve --bg http://127.0.0.1:47832\n3. In Android Settings, enter the HTTPS address Tailscale shows, then paste the pairing token.\n\nYour PC and LearnHarness must stay on. The token permits reading your learning library and using your configured AI providers. It does not grant access to provider keys or settings.\n\nStopping the connection revokes the token.',
        buttons:['Copy pairing token','Stop and revoke','Close'], defaultId:2, cancelId:2});
      if (result.response === 0) clipboard.writeText(token);
      if (result.response === 1) await stop();
    } catch (error) { await dialog.showMessageBox({type:'error', title:'Phone connection', message:error.message}); }
  }
  app.on('before-quit', () => { server?.closeAllConnections(); server?.close(); });
  return {show, stop};
}
module.exports = {createMobileConnection};
