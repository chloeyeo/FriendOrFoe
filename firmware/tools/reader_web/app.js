'use strict';
const el = (id) => document.getElementById(id);
let currentState = null;
let actionPending = false;
let localError = '';
let renderedLogSequence = -1;
const reasonNames = {
  NONE: '인증 성공', NO_RESPONSE: '3초 내 무선 응답 없음', AUTH_FAILED: '응답 인증 실패',
  INVALID_MESSAGE: '응답 형식 오류', RADIO_ERROR: '무선 장치 오류', BUSY: 'reader가 다른 작업 중입니다.',
  KEY_NOT_CONFIGURED: '비밀키가 설정되지 않았습니다.', INVALID_REQUEST_ID: '잘못된 요청 ID',
  ACK_TIMEOUT: '명령 수락 확인 시간 초과', RESULT_TIMEOUT: '시리얼 결과 확인 시간 초과',
  READER_REBOOTED: 'reader 재부팅', SERIAL_ERROR: 'USB 시리얼 통신 오류', DISCONNECTED: '연결 해제'
};
async function api(path, body) {
  const response = await fetch(path, body === undefined ? {cache: 'no-store'} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '요청에 실패했습니다.');
  return data;
}
function render(state) {
  currentState = state;
  const connectionNames = {disconnected: '연결 안 됨', connecting: 'reader 확인 중', connected: '연결됨', disconnecting: '연결 해제 중', error: '연결 오류'};
  el('connection').textContent = `${connectionNames[state.connection] || state.connection}${state.port ? ' · ' + state.port : ''}`;
  const connected = ['connecting', 'connected', 'disconnecting'].includes(state.connection);
  el('connect').disabled = connected || actionPending || !el('port').value;
  if (connected && state.port) {
    if (![...el('port').options].some((option) => option.value === state.port)) el('port').add(new Option(state.port, state.port));
    el('port').value = state.port;
  }
  el('port').disabled = connected;
  el('disconnect').disabled = !connected || state.connection === 'disconnecting' || actionPending;
  el('query').disabled = !state.can_query || actionPending;
  el('status').disabled = state.connection !== 'connected' || actionPending;
  const drone = state.drone || {connection: 'disconnected'};
  const droneConnected = ['connecting', 'connected', 'disconnecting'].includes(drone.connection);
  el('drone-connection').textContent = `${connectionNames[drone.connection] || drone.connection}${drone.port ? ' · ' + drone.port : ''}`;
  if (droneConnected && drone.port) {
    if (![...el('drone-port').options].some(option => option.value === drone.port)) el('drone-port').add(new Option(drone.port, drone.port));
    el('drone-port').value = drone.port;
  }
  el('drone-port').disabled = droneConnected;
  el('drone-connect').disabled = droneConnected || actionPending || !el('drone-port').value;
  el('drone-disconnect').disabled = !droneConnected || actionPending;
  el('drone-status').disabled = drone.connection !== 'connected' || actionPending;
  const device = drone.device;
  el('drone-detail').textContent = device ? `drone · ${device.state} · 키 ${device.key_configured ? '설정됨' : '미설정'} · 무선 ${device.radio_ready ? '준비됨' : '오류'}` : '연결 후 Drone의 수신 대기·키·무선 상태를 확인합니다.';
  const reader = state.reader;
  el('reader-state').textContent = reader ? ({IDLE: '대기 중', QUERYING: '응답 대기 중', COOLDOWN: '재질의 대기 · 시작 후 3초'}[reader.state] || reader.state) : '연결 대기';
  el('reader-detail').textContent = reader ? `reader · 인터페이스 v${reader.interface_version} · 키 ${reader.key_configured ? '설정됨' : '미설정'} · 무선 ${reader.radio_ready ? '준비됨' : '오류'}` : '연결 후 reader 역할과 인터페이스 v2를 확인합니다.';
  const active = state.active_request;
  const result = state.last_result;
  el('request-id').textContent = active?.request_id || result?.request_id || '—';
  el('verdict-card').className = '';
  if (active) {
    el('received').textContent = '확인 중'; el('verdict').textContent = '대기 중';
    el('reason').textContent = active.phase === 'WAIT_RESULT' ? '최대 3초 동안 응답을 기다립니다.' : '명령 수락을 확인하고 있습니다.';
    el('elapsed').textContent = '—'; el('query-number').textContent = '명령당 질의 1회';
  } else if (result) {
    el('received').textContent = result.response_received === true ? '수신됨' : result.response_received === false ? '수신 없음' : '확인 불가';
    const communication = result.category === 'COMMUNICATION_ERROR';
    el('verdict').textContent = communication ? '통신 오류' : result.category === 'REJECTED' ? '명령 거절' : result.verdict;
    el('reason').textContent = reasonNames[result.reason] || result.reason;
    el('elapsed').textContent = result.response_ms == null ? '—' : `${result.response_ms} ms`;
    el('query-number').textContent = result.query ? `질의 #${result.query}` : '무선 판정이 확인되지 않았습니다.';
    el('verdict-card').className = communication ? 'communication' : result.verdict === 'FRIEND' ? 'friend' : 'unknown';
  } else {
    el('received').textContent = '—'; el('verdict').textContent = '—'; el('elapsed').textContent = '—';
    el('reason').textContent = '아직 질의하지 않았습니다.'; el('query-number').textContent = '응답까지 걸린 시간';
  }
  const error = localError || state.error || state.drone?.error;
  el('error').hidden = !error; el('error').textContent = error || '';
  const sequence = state.logs.at(-1)?.received_at || state.logs.at(-1)?.sequence || 0;
  if (sequence !== renderedLogSequence) {
    const logs = el('logs');
    const wasAtBottom = logs.scrollTop + logs.clientHeight >= logs.scrollHeight - 30;
    logs.textContent = state.logs.filter(entry => el('log-source').value === 'all' || entry.source === el('log-source').value).map((entry) => `${entry.time} [${(entry.source || 'reader').toUpperCase()}] ${entry.direction}  ${entry.text}`).join('\n') || '표시할 이벤트가 없습니다.';
    if (wasAtBottom) logs.scrollTop = logs.scrollHeight;
    renderedLogSequence = sequence;
  }
}
async function refreshPorts() {
  try {
    const {ports} = await api('/api/ports');
    for (const id of ['port', 'drone-port']) {
      const previous = el(id).value;
      el(id).replaceChildren(new Option('포트를 선택하세요', ''));
      for (const item of ports) el(id).add(new Option(`${item.port} · ${item.description}`, item.port));
      if (ports.some(item => item.port === previous)) el(id).value = previous;
    }
    if (currentState) render(currentState);
  } catch (error) { localError = error.message; if (currentState) render(currentState); }
}
async function action(path, body = {}) {
  if (actionPending) return;
  actionPending = true; localError = '';
  if (currentState) render(currentState);
  try { await api(path, body); render(await api('/api/state')); }
  catch (error) { localError = error.message; }
  finally { actionPending = false; if (currentState) render(currentState); }
}
el('refresh').addEventListener('click', refreshPorts);
el('port').addEventListener('change', () => { if (currentState) render(currentState); });
el('connect').addEventListener('click', () => action('/api/connect', {port: el('port').value}));
el('disconnect').addEventListener('click', () => action('/api/disconnect'));
el('status').addEventListener('click', () => action('/api/status', {role: 'reader'}));
el('drone-port').addEventListener('change', () => { if (currentState) render(currentState); });
el('drone-connect').addEventListener('click', () => action('/api/connect', {role: 'drone', port: el('drone-port').value}));
el('drone-disconnect').addEventListener('click', () => action('/api/disconnect', {role: 'drone'}));
el('drone-status').addEventListener('click', () => action('/api/status', {role: 'drone'}));
el('log-source').addEventListener('change', () => { renderedLogSequence = -1; if (currentState) render(currentState); });
el('query').addEventListener('click', () => action('/api/query'));
el('copy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(el('logs').textContent); }
  catch { localError = '로그를 직접 선택하여 복사해 주세요.'; if (currentState) render(currentState); }
});
async function poll() {
  try {
    const state = await api('/api/state');
    if (localError === '로컬 서버와 연결할 수 없습니다. 실행 중인 터미널을 확인해 주세요.') localError = '';
    render(state);
  }
  catch { localError = '로컬 서버와 연결할 수 없습니다. 실행 중인 터미널을 확인해 주세요.'; if (currentState) { render({...currentState, can_query: false}); } }
  finally { setTimeout(poll, 250); }
}
refreshPorts(); poll(); // 조회만 합니다. 페이지 로드/복구 시 QUERY를 보내지 않습니다.
