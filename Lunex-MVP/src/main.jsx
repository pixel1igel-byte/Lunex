import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { io } from 'socket.io-client';
import {
  MoonStar, Search, Plus, MessageCircle, Users, Phone, Bell, Settings2, Video, Mic, PhoneOff, PhoneIncoming,
  Hash, Volume2, ChevronDown, Send, Paperclip, Smile, Palette, Image as ImageIcon,
  Type, Sparkles, Download, Upload, RotateCcw, Menu, X, ArrowLeft, Check,
  SlidersHorizontal, PanelLeftClose, CircleHelp, ShieldCheck, Server, Headphones
} from 'lucide-react';
import './style.css';

// In production the frontend and API share one HTTPS origin; local Vite uses port 3001.
const API = import.meta.env.DEV ? '' : location.origin;
const socket = io(API, { autoConnect: false, auth: { token: localStorage.getItem('lunex-token') || '' } });
const DEFAULT_THEME = {
  accent: '#b56bff', background: '#100c19', panel: '#191426', wallpaper: '',
  bubbleRadius: 18, fontSize: 14, transparency: 100, blur: 0, animations: true
};

function App() {
  const [user, setUser] = useState('');
  const [socketConnected, setSocketConnected] = useState(socket.connected);
  const [userId, setUserId] = useState('');
  const [token, setToken] = useState(() => localStorage.getItem('lunex-token') || '');
  const [authMode, setAuthMode] = useState('login');
  const [authPassword, setAuthPassword] = useState('');
  const [authUsername, setAuthUsername] = useState('');
  const [authLoading, setAuthLoading] = useState(true);
  const [communities, setCommunities] = useState([]);
  const [activeCommunity, setActiveCommunity] = useState('');
  const [activeChannel, setActiveChannel] = useState('general');
  const [messages, setMessages] = useState([]);
  const [dms, setDms] = useState([]);
  const [dmMessages, setDmMessages] = useState([]);
  const [dmDraft, setDmDraft] = useState('');
  const [draft, setDraft] = useState('');
  const [tab, setTab] = useState('chats');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  const [chatSearch, setChatSearch] = useState('');
  const [emojiOpen, setEmojiOpen] = useState(false);
  const [notifications, setNotifications] = useState(() => { try { return JSON.parse(localStorage.getItem('lunex-notifications') || '[]').slice(0,50); } catch { return []; } });
  const [theme, setTheme] = useState(() => {
    try { return { ...DEFAULT_THEME, ...JSON.parse(localStorage.getItem('lunex-theme') || '{}') }; }
    catch { return DEFAULT_THEME; }
  });
  const [dm, setDm] = useState(null);
  const [notice, setNotice] = useState('');
  const [newCommunity, setNewCommunity] = useState(false);
  const [newChannel, setNewChannel] = useState(false);
  const [newDm, setNewDm] = useState(false);
  const [dmUsername, setDmUsername] = useState('');
  const [newName, setNewName] = useState('');
  const [profileName, setProfileName] = useState('');
  const bottomRef = useRef(null);
  const fileRef = useRef(null);
  const wallpaperRef = useRef(null);
  const peerRef = useRef(null);
  const pendingIceRef = useRef([]);
  const pendingLocalIceRef = useRef([]);
  const callTimerRef = useRef(null);
  const localStreamRef = useRef(null);
  const callRef = useRef(null);
  const [callState, setCallState] = useState('idle');
  const [callMode, setCallMode] = useState('audio');
  const [incomingCall, setIncomingCall] = useState(null);
  const [remoteStream, setRemoteStream] = useState(null);
  const [localStream, setLocalStream] = useState(null);
  const [callMuted, setCallMuted] = useState(false);
  const [cameraOff, setCameraOff] = useState(false);
  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);
  const remoteAudioRef = useRef(null);

  useEffect(() => { try { localStorage.setItem('lunex-notifications', JSON.stringify(notifications.slice(0,50))); } catch {} }, [notifications]);

  useEffect(() => {
    const onDmNew = event => {
      const msg = event?.message;
      if (!msg || msg.userId === userId) return;
      setDms(prev => prev.map(c => c.id === event.conversationId ? { ...c, lastMessage: msg.text, time: msg.time } : c));
      if (event.conversationId !== dm?.id) {
        setNotifications(prev => [{ id: msg.id, type: 'dm', conversationId: event.conversationId, user: msg.user, text: msg.text, time: msg.time, read: false }, ...prev.filter(n => n.id !== msg.id)].slice(0,50));
      }
    };
    socket.on('dm:new', onDmNew);
    return () => socket.off('dm:new', onDmNew);
  }, [userId, dm?.id]);

  useEffect(() => {
    const onConnect = () => setSocketConnected(true);
    const onDisconnect = () => setSocketConnected(false);
    socket.on('connect', onConnect); socket.on('disconnect', onDisconnect);
    setSocketConnected(socket.connected);
    return () => { socket.off('connect', onConnect); socket.off('disconnect', onDisconnect); };
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (!token) { setAuthLoading(false); return; }
    const headers = { Authorization: `Bearer ${token}` };
    fetch(`${API}/api/me`, { headers }).then(r => { if (!r.ok) throw new Error('session'); return r.json(); }).then(data => {
      if (cancelled) return;
      setUser(data.user.displayName); setUserId(data.user.id); socket.auth = { token }; if (!socket.connected) socket.connect();
      return fetch(`${API}/api/bootstrap`, { headers }).then(r => r.json()).then(data => {
        if (cancelled) return; setCommunities(data.communities || []);
        const first = data.communities?.[0]; if (first) { setActiveCommunity(first.id); setActiveChannel(first.channels?.[0]?.id || 'general'); }
        return fetch(`${API}/api/dms`, { headers }).then(r => r.ok ? r.json() : []).then(items => { if (!cancelled) setDms(items); });
      });
    }).catch(() => { if (!cancelled) { localStorage.removeItem('lunex-token'); setToken(''); setUser(''); setNotice('Сессия завершилась. Войди снова.'); } })
      .finally(() => { if (!cancelled) setAuthLoading(false); });
    socket.on('communities:changed', setCommunities);
    socket.on('message:error', event => setNotice(event.error || 'Не удалось отправить сообщение.'));
    return () => { cancelled = true; socket.off('communities:changed', setCommunities); socket.off('message:error'); };
  }, [token]);

  useEffect(() => {
    if (!activeChannel) return;
    fetch(`${API}/api/messages/${encodeURIComponent(activeChannel)}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.ok ? r.json() : []).then(setMessages).catch(() => {});
    socket.emit('channel:join', activeChannel);
    const onNew = event => {
      if (event.channelId === activeChannel) setMessages(prev => [...prev, event.message].slice(-500));
    };
    socket.on('message:new', onNew);
    return () => socket.off('message:new', onNew);
  }, [activeChannel, token]);

  useEffect(() => {
    if (!dm || !token) { setDmMessages([]); return; }
    fetch(`${API}/api/dms/${encodeURIComponent(dm.id)}/messages`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.ok ? r.json() : []).then(setDmMessages).catch(() => {});
    socket.emit('dm:join', dm.id);
    const onNew = event => { if (event.conversationId === dm.id) setDmMessages(prev => prev.some(m => m.id === event.message.id) ? prev : [...prev, event.message].slice(-500)); };
    socket.on('dm:new', onNew);
    return () => socket.off('dm:new', onNew);
  }, [dm?.id, token]);

  function closeCall(notify = true) {
    const current = callRef.current;
    if (notify && current) socket.emit('call:end', { callId: current.callId, conversationId: current.conversationId });
    try { peerRef.current?.close(); } catch {}
    peerRef.current = null; pendingIceRef.current = []; pendingLocalIceRef.current = [];
    if (callTimerRef.current) clearTimeout(callTimerRef.current); callTimerRef.current = null;
    localStreamRef.current?.getTracks().forEach(track => track.stop());
    localStreamRef.current = null; callRef.current = null;
    setLocalStream(null); setRemoteStream(null); setIncomingCall(null); setCallState('idle'); setCallMuted(false); setCameraOff(false);
  }
  async function createPeer(call, stream) {
    let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
    try { const response = await fetch(`${API}/api/call-config`, { headers: { Authorization: `Bearer ${token}` } }); if (response.ok) { const config = await response.json(); if (Array.isArray(config.iceServers) && config.iceServers.length) iceServers = config.iceServers; } } catch {}
    const pc = new RTCPeerConnection({ iceServers });
    peerRef.current = pc;
    stream.getTracks().forEach(track => pc.addTrack(track, stream));
    pc.ontrack = event => setRemoteStream(event.streams[0]);
    pc.onconnectionstatechange = () => {
      if (callRef.current?.callId !== call.callId) return;
      if (pc.connectionState === 'connected') { if (callTimerRef.current) clearTimeout(callTimerRef.current); callTimerRef.current = null; setCallState('connected'); }
      if (pc.connectionState === 'failed') { closeCall(false); setNotice('Соединение звонка потеряно. Проверь сеть или TURN-сервер.'); }
    };
    pc.onicecandidate = event => {
      if (!event.candidate || callRef.current?.callId !== call.callId) return;
      const payload = { callId: call.callId, conversationId: call.conversationId, signal: { candidate: event.candidate } };
      if (callRef.current.initiator && !callRef.current.signalingReady) pendingLocalIceRef.current.push(payload);
      else socket.emit('call:signal', payload);
    };
    return pc;
  }
  async function startCall(mode) {
    if (!dm || callState !== 'idle') return;
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('Браузер не поддерживает доступ к микрофону/камере. Открой Lunex по HTTPS.');
      setCallMode(mode); setCallState('calling');
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: mode === 'video' });
      localStreamRef.current = stream; setLocalStream(stream);
      const call = { callId: crypto.randomUUID(), conversationId: dm.id, peerId: dm.other?.id, mode, initiator: true, signalingReady: false };
      callRef.current = call;
      const pc = await createPeer(call, stream);
      const offer = await pc.createOffer(); await pc.setLocalDescription(offer);
      socket.emit('call:invite', { ...call, toUserId: call.peerId, offer: pc.localDescription });
      call.signalingReady = true;
      for (const payload of pendingLocalIceRef.current.splice(0)) socket.emit('call:signal', payload);
      callTimerRef.current = setTimeout(() => { if (callRef.current?.callId === call.callId && peerRef.current?.connectionState !== 'connected') { closeCall(true); setNotice('Не удалось дозвониться. Проверь подключение и TURN-сервер.'); } }, 35000);
    } catch (error) { closeCall(false); setNotice(error?.message || 'Не удалось начать звонок. Проверь разрешения микрофона и камеры.'); }
  }
  async function acceptCall() {
    const incoming = incomingCall;
    if (!incoming) return;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: incoming.mode === 'video' });
      localStreamRef.current = stream; setLocalStream(stream);
      const call = { callId: incoming.callId, conversationId: incoming.conversationId, peerId: incoming.caller.id, mode: incoming.mode, initiator: false, signalingReady: true };
      callRef.current = call; setCallMode(incoming.mode); setCallState('connecting');
      const pc = await createPeer(call, stream);
      await pc.setRemoteDescription(new RTCSessionDescription(incoming.offer));
      for (const item of pendingIceRef.current.filter(item => item.callId === call.callId)) { try { await pc.addIceCandidate(new RTCIceCandidate(item.signal.candidate)); } catch {} }
      pendingIceRef.current = pendingIceRef.current.filter(item => item.callId !== call.callId);
      const answer = await pc.createAnswer(); await pc.setLocalDescription(answer);
      socket.emit('call:accept', { callId: call.callId, conversationId: call.conversationId, answer: pc.localDescription });
      setIncomingCall(null);
    } catch (error) { setNotice(error?.message || 'Не удалось принять звонок.'); closeCall(false); }
  }
  useEffect(() => {
    const onIncoming = event => { if (callRef.current) { socket.emit('call:reject', event); return; } setIncomingCall(event); setCallMode(event.mode); setCallState('incoming'); };
    const onAccepted = async event => { if (callRef.current?.callId !== event.callId || !peerRef.current) return; try { await peerRef.current.setRemoteDescription(new RTCSessionDescription(event.answer)); for (const item of pendingIceRef.current.filter(item => item.callId === event.callId)) { try { await peerRef.current.addIceCandidate(new RTCIceCandidate(item.signal.candidate)); } catch {} } pendingIceRef.current = pendingIceRef.current.filter(item => item.callId !== event.callId); setCallState('connecting'); } catch { setNotice('Не удалось установить соединение звонка.'); closeCall(false); } };
    const onSignal = async event => {
      if (!event.signal?.candidate) return;
      if (callRef.current?.callId !== event.callId || !peerRef.current || !peerRef.current.remoteDescription) { pendingIceRef.current.push(event); if (pendingIceRef.current.length > 100) pendingIceRef.current.shift(); return; }
      try { await peerRef.current.addIceCandidate(new RTCIceCandidate(event.signal.candidate)); } catch { pendingIceRef.current.push(event); }
    };
    const onRejected = event => { if (callRef.current?.callId === event.callId) { closeCall(false); setNotice('Собеседник отклонил звонок.'); } };
    const onEnded = event => { if (callRef.current?.callId === event.callId) { closeCall(false); setNotice('Звонок завершён.'); } };
    socket.on('call:incoming', onIncoming); socket.on('call:accepted', onAccepted); socket.on('call:signal', onSignal); socket.on('call:rejected', onRejected); socket.on('call:ended', onEnded);
    return () => { socket.off('call:incoming', onIncoming); socket.off('call:accepted', onAccepted); socket.off('call:signal', onSignal); socket.off('call:rejected', onRejected); socket.off('call:ended', onEnded); };
  }, []);
  useEffect(() => { if (localVideoRef.current && localStream) localVideoRef.current.srcObject = localStream; }, [localStream, callState]);
  useEffect(() => { if (remoteVideoRef.current && remoteStream) remoteVideoRef.current.srcObject = remoteStream; if (remoteAudioRef.current && remoteStream) remoteAudioRef.current.srcObject = remoteStream; }, [remoteStream, callState]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: theme.animations ? 'smooth' : 'auto' }); }, [messages, dmMessages, theme.animations]);
  useEffect(() => {
    localStorage.setItem('lunex-theme', JSON.stringify(theme));
    document.documentElement.style.setProperty('--accent', theme.accent);
    document.documentElement.style.setProperty('--app-bg', theme.background);
    document.documentElement.style.setProperty('--panel', theme.panel);
    document.documentElement.style.setProperty('--bubble-radius', `${theme.bubbleRadius}px`);
    document.documentElement.style.setProperty('--font-size', `${theme.fontSize}px`);
    document.documentElement.style.setProperty('--panel-alpha', `${theme.transparency / 100}`);
    document.documentElement.style.setProperty('--panel-blur', `${theme.blur}px`);
    document.documentElement.style.setProperty('--wallpaper', theme.wallpaper ? `url("${theme.wallpaper}")` : 'none');
  }, [theme]);

  const community = communities.find(c => c.id === activeCommunity) || communities[0];
  const channel = community?.channels?.find(c => c.id === activeChannel);
  const searchTerm = chatSearch.trim().toLowerCase();
  const visibleDms = dms.filter(c => `${c.other?.displayName || ''} ${c.other?.username || ''} ${c.lastMessage || ''}`.toLowerCase().includes(searchTerm));
  const visibleCommunities = communities.filter(c => `${c.name} ${c.description || ''} ${c.channels.map(ch => ch.name).join(' ')}`.toLowerCase().includes(searchTerm));

  function selectChannel(c, ch) { setActiveCommunity(c.id); setActiveChannel(ch.id); setTab('chats'); setMobileNav(false); setDm(null); }
  function sendDm(e) { e?.preventDefault(); const text = dmDraft.trim(); if (!text || !dm) return; if (!socket.connected) { setNotice('Нет соединения с сервером. Подожди подключения и отправь сообщение ещё раз.'); return; } socket.emit('dm:send', { conversationId: dm.id, text }); setDmDraft(''); }
  async function openDm(e) { e?.preventDefault(); const username = dmUsername.trim().replace(/^@/, ''); if (!username) return; try { const res = await fetch(`${API}/api/dms`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ username }) }); const data = await res.json(); if (!res.ok) { setNotice(data.error || 'Не удалось открыть чат.'); return; } setDms(prev => [data, ...prev.filter(c => c.id !== data.id)]); setDm(data); setActiveChannel(''); setNewDm(false); setDmUsername(''); setTab('chats'); } catch { setNotice('Сервер недоступен.'); } }
  function sendMessage(e) {
    e?.preventDefault();
    const text = draft.trim();
    if (!text || !user || !channel || channel.type === 'voice') return;
    if (!socket.connected) { setNotice('Нет соединения с сервером. Подожди подключения и отправь сообщение ещё раз.'); return; }
    socket.emit('message:send', { channelId: activeChannel, user, text });
    setDraft('');
  }
  async function createCommunity() {
    const name = newName.trim(); if (!name) return;
    try { const res = await fetch(`${API}/api/communities`, { method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${token}`}, body:JSON.stringify({name}) }); const data = await res.json().catch(()=>({}));
      if (!res.ok) { setNotice(data.error || 'Не удалось создать сообщество.'); return; }
      const c = data; setCommunities(prev => [...prev.filter(item=>item.id!==c.id),c]); setActiveCommunity(c.id); setActiveChannel(c.channels[0].id); setNewCommunity(false); setNewName(''); setNotice('Сообщество создано.');
    } catch { setNotice('Сервер недоступен. Сообщество не создано.'); }
  }
  async function createChannel() {
    const name = newName.trim(); if (!name || !community) return;
    try { const res = await fetch(`${API}/api/communities/${community.id}/channels`, { method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${token}`}, body:JSON.stringify({name}) }); const data = await res.json().catch(()=>({}));
      if (!res.ok) { setNotice(data.error || 'Не удалось создать канал.'); return; }
      const ch = data; setCommunities(prev=>prev.map(c=>c.id===community.id?{...c,channels:[...c.channels.filter(item=>item.id!==ch.id),ch]}:c)); setActiveChannel(ch.id); setNewChannel(false); setNewName(''); setNotice('Канал создан.');
    } catch { setNotice('Сервер недоступен. Канал не создан.'); }
  }
  async function login(e) {
    e.preventDefault();
    const payload = authMode === 'register'
      ? { username: authUsername, displayName: profileName, password: authPassword }
      : { username: authUsername, password: authPassword };
    try {
      const response = await fetch(`${API}/api/auth/${authMode}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      const data = await response.json();
      if (!response.ok) { setNotice(data.error || 'Не удалось войти.'); return; }
      localStorage.setItem('lunex-token', data.token); setToken(data.token); setUser(data.user.displayName); setUserId(data.user.id);
      setAuthPassword(''); setAuthLoading(false);
    } catch { setNotice('Сервер недоступен. Запусти Lunex на хостинге или компьютере.'); }
  }
  async function logout() {
    closeCall(true);
    try { await fetch(`${API}/api/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } }); } catch {}
    socket.disconnect(); localStorage.removeItem('lunex-token'); setToken(''); setUser(''); setUserId(''); setCommunities([]); setMessages([]); setActiveChannel('');
  }
  function changeTheme(key, value) { setTheme(prev => ({...prev, [key]: value})); }
  function exportTheme() {
    const blob = new Blob([JSON.stringify({ format:'lunex-theme', version:1, theme }, null, 2)], {type:'application/json'});
    const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'lunex-theme.json'; a.click(); URL.revokeObjectURL(url);
  }
  function importTheme(e) {
    const file = e.target.files?.[0]; if (!file) return;
    if (file.size > 2 * 1024 * 1024) { setNotice('Файл темы больше 2 МБ.'); return; }
    const reader = new FileReader();
    reader.onload = () => { try {
      const parsed = JSON.parse(reader.result); if (parsed.format !== 'lunex-theme' || !parsed.theme) throw new Error();
      setTheme({...DEFAULT_THEME, ...parsed.theme}); setNotice('Тема импортирована.');
    } catch { setNotice('Не удалось прочитать файл темы.'); } };
    reader.readAsText(file); e.target.value = '';
  }
  function chooseWallpaper(e) {
    const file = e.target.files?.[0]; if (!file) return;
    if (!file.type.startsWith('image/')) { setNotice('Выберите файл изображения.'); return; }
    if (file.size > 4 * 1024 * 1024) { setNotice('Изображение должно быть меньше 4 МБ.'); return; }
    const reader = new FileReader(); reader.onload = () => changeTheme('wallpaper', reader.result); reader.readAsDataURL(file); e.target.value = '';
  }

  if (authLoading) return <div className="login-wrap"><div className="login-card"><div className="brand-mark"><MoonStar size={34}/></div><h1>LUNEX</h1><p>Подключаем защищённую сессию…</p></div></div>;
  if (!user) return <div className="login-wrap"><form className="login-card" onSubmit={login}><div className="brand-mark"><MoonStar size={34}/></div><h1>LUNEX</h1><p>Чаты и сообщества. Твоё пространство.</p><div className="auth-tabs"><button type="button" className={authMode==='login'?'picked':''} onClick={()=>setAuthMode('login')}>Войти</button><button type="button" className={authMode==='register'?'picked':''} onClick={()=>setAuthMode('register')}>Создать аккаунт</button></div><label>Логин</label><input autoComplete="username" required minLength="3" maxLength="24" value={authUsername} onChange={e=>setAuthUsername(e.target.value)} placeholder="например, lunex_user" />{authMode==='register' && <><label>Отображаемое имя</label><input autoComplete="nickname" required maxLength="32" value={profileName} onChange={e=>setProfileName(e.target.value)} placeholder="Как тебя будут видеть" /></>}<label>Пароль (минимум 10 символов)</label><input type="password" autoComplete={authMode==='register'?'new-password':'current-password'} required minLength={10} maxLength={128} value={authPassword} onChange={e=>setAuthPassword(e.target.value)} placeholder="Пароль" /><button className="primary wide" disabled={!authUsername.trim() || authPassword.length < 10 || (authMode==='register'&&!profileName.trim())}>{authMode==='register'?'Создать аккаунт':'Войти в Lunex'} <span>→</span></button><small>Аккаунты хранятся на сервере Lunex. Используй уникальный пароль.</small></form></div>;

  return <div className="app-shell">
    <aside className={`rail ${mobileNav ? 'show' : ''}`}>
      <div className="rail-logo"><MoonStar size={24}/></div>
      <button className={`rail-item ${tab==='chats'?'active':''}`} title="Чаты" onClick={()=>{setTab('chats');setMobileNav(false)}}><MessageCircle/></button>
      <button className={`rail-item ${tab==='communities'?'active':''}`} title="Сообщества" onClick={()=>{setTab('communities');setMobileNav(false)}}><Users/></button>
      <button className={`rail-item ${tab==='calls'?'active':''}`} title="Звонки" onClick={()=>{setTab('calls');setMobileNav(false)}}><Phone/></button>
      <button className={`rail-item ${tab==='notifications'?'active':''}`} title="Уведомления" onClick={()=>{setTab('notifications');setMobileNav(false)}}><Bell/></button>
      <div className="rail-spacer"/>
      <button className="rail-item" title="Настройки" onClick={()=>{setSettingsOpen(true);setMobileNav(false)}}><Settings2/></button>
      <div className="rail-avatar">{user.slice(0,1).toUpperCase()}</div>
    </aside>

    <aside className={`sidebar ${mobileNav ? 'show' : ''}`}>
      <header className="sidebar-header"><div><div className="brand">LUNEX <span className={`online-dot ${socketConnected?'connected':'offline'}`} title={socketConnected?'Соединение с сервером активно':'Нет соединения с сервером'}/></div><div className="muted tiny">Твоё цифровое пространство</div></div><button className="icon-btn" onClick={()=>setNewCommunity(true)} title="Создать сообщество"><Plus/></button></header>
      <div className="search-box"><Search size={16}/><input value={chatSearch} onChange={e=>setChatSearch(e.target.value)} placeholder="Поиск чатов и каналов"/></div>
      <nav className="mobile-tabs" aria-label="Навигация"><button className={tab==='chats'?'active':''} onClick={()=>{setTab('chats');setMobileNav(false)}}><MessageCircle size={16}/>Чаты</button><button className={tab==='communities'?'active':''} onClick={()=>{setTab('communities');setMobileNav(false)}}><Users size={16}/>Сообщества</button><button className={tab==='calls'?'active':''} onClick={()=>{setTab('calls');setMobileNav(false)}}><Phone size={16}/>Звонки</button></nav>
      {tab === 'chats' && <>
        <div className="section-heading"><span>ЛИЧНЫЕ ЧАТЫ</span><button className="tiny-icon" title="Новый личный чат" onClick={()=>setNewDm(true)}><Plus size={15}/></button></div>
        {visibleDms.map(c=><button key={c.id} className={`dm-row ${dm?.id===c.id?'selected':''}`} onClick={()=>{setDm(c);setActiveChannel('');setMobileNav(false)}}><div className="avatar">{(c.other?.displayName || '?').slice(0,1).toUpperCase()}</div><div className="row-main"><strong>{c.other?.displayName || 'Пользователь'}</strong><span>{c.lastMessage || `@${c.other?.username || ''}`}</span></div><small>{c.time ? new Date(c.time).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}) : ''}</small></button>)}
        <div className="section-heading"><span>СООБЩЕСТВА</span><button className="tiny-icon" onClick={()=>setNewCommunity(true)}><Plus size={15}/></button></div>
        {visibleCommunities.map(c=><div key={c.id} className="community-block"><button className={`community-title ${activeCommunity===c.id?'chosen':''}`} onClick={()=>setActiveCommunity(c.id)}><span className="community-icon">{c.name.slice(0,1).toUpperCase()}</span><span>{c.name}</span><ChevronDown size={14}/></button>{(activeCommunity===c.id || tab==='communities') && c.channels.map(ch=><button key={ch.id} className={`channel-row ${activeChannel===ch.id?'chosen':''}`} onClick={()=>selectChannel(c,ch)}>{ch.type==='voice'?<Volume2 size={15}/>:<Hash size={15}/>}<span>{ch.name}</span></button>)}</div>)}
        {community && <button className="add-channel" onClick={()=>setNewChannel(true)}><Plus size={15}/> Добавить канал</button>}
      </>}
      {tab === 'communities' && <div className="community-browser"><div className="section-label">ТВОИ СООБЩЕСТВА</div>{communities.map(c=><button className="browse-card" key={c.id} onClick={()=>{setActiveCommunity(c.id);setTab('chats')}}><div className="community-icon big">{c.name.slice(0,1)}</div><div><strong>{c.name}</strong><p>{c.description}</p><small>{c.channels.length} каналов</small></div></button>)}<button className="primary wide" onClick={()=>setNewCommunity(true)}><Plus size={16}/> Создать сообщество</button></div>}
      {tab === 'calls' && <div className="empty-pane"><Phone/><h3>Звонки</h3><p>Выбери собеседника ниже, чтобы начать аудио- или видеозвонок.</p>{visibleDms.length===0 ? <small>Сначала создай личный чат.</small> : visibleDms.map(c=><div className="call-contact" key={c.id}><div className="avatar">{(c.other?.displayName||'?').slice(0,1).toUpperCase()}</div><div className="row-main"><strong>{c.other?.displayName||'Пользователь'}</strong><span>@{c.other?.username||''}</span></div><button className="icon-btn" title="Голосовой звонок" onClick={()=>{setDm(c);setActiveChannel('');setTab('chats');setNotice('Чат открыт. Нажми на трубку или камеру вверху, чтобы позвонить.')}}><Phone size={18}/></button><button className="icon-btn" title="Видеозвонок" onClick={()=>{setDm(c);setActiveChannel('');setTab('chats');setNotice('Чат открыт. Нажми на трубку или камеру вверху, чтобы позвонить.')}}><Video size={18}/></button></div>)}</div>}
      {tab === 'notifications' && <div className="empty-pane"><Bell/><h3>Уведомления</h3><p>Здесь будут упоминания и события сообществ.</p></div>}
      <footer className="profile-footer"><div className="avatar self">{user.slice(0,1).toUpperCase()}</div><div className="row-main"><strong>{user}</strong><span>Аккаунт Lunex</span></div><button className="icon-btn" title="Выйти" onClick={logout}><ArrowLeft size={18}/></button><button className="icon-btn" title="Настройки" onClick={()=>setSettingsOpen(true)}><Settings2 size={18}/></button></footer>
    </aside>

    <main className="main-pane">
      {dm ? <><header className="chat-header"><button className="mobile-menu" onClick={()=>setMobileNav(!mobileNav)}><Menu/></button><div className="avatar">{(dm.other?.displayName || '?').slice(0,1).toUpperCase()}</div><div className="channel-meta"><strong>{dm.other?.displayName || 'Личный чат'}</strong><span>@{dm.other?.username || ''} · личная переписка</span></div><div className="header-actions"><button className="icon-btn" title="Голосовой звонок" disabled={callState!=='idle'} onClick={()=>startCall('audio')}><Phone size={19}/></button><button className="icon-btn" title="Видеозвонок" disabled={callState!=='idle'} onClick={()=>startCall('video')}><Video size={19}/></button></div></header><div className="messages">{dmMessages.length===0 && <div className="date-divider"><span>НАЧАЛО ЛИЧНОЙ ПЕРЕПИСКИ</span></div>}{dmMessages.map(m=><article className={`message ${m.userId===userId?'own':''}`} key={m.id}><div className="avatar">{(m.user || '?').slice(0,1).toUpperCase()}</div><div className="message-body"><div className="message-heading"><strong>{m.user}</strong><time>{new Date(m.time).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}</time></div><div className="bubble">{m.text}</div></div></article>)}<div ref={bottomRef}/></div><form className="composer" onSubmit={sendDm}><input value={dmDraft} onChange={e=>setDmDraft(e.target.value)} placeholder={`Сообщение для ${dm.other?.displayName || 'пользователя'}…`} maxLength={4000}/><button className="send-btn" disabled={!dmDraft.trim()} title="Отправить"><Send size={18}/></button></form></> :
      tab === 'communities' ? <div className="directory-page"><header className="directory-heading"><div><span className="eyebrow">LUNEX / ПРОСТРАНСТВА</span><h1>Сообщества</h1><p>Выбери пространство и открой нужный канал.</p></div><button className="primary" onClick={()=>setNewCommunity(true)}><Plus size={16}/> Создать</button></header>{visibleCommunities.length===0 ? <div className="directory-empty">Ничего не найдено по запросу «{chatSearch}».</div> : <div className="directory-grid">{visibleCommunities.map(c=><article className="directory-card" key={c.id}><div className="directory-card-top"><div className="community-icon big">{c.name.slice(0,1).toUpperCase()}</div><div><h3>{c.name}</h3><p>{c.description || 'Сообщество Lunex'}</p></div></div><div className="directory-channels">{c.channels.map(ch=><button key={ch.id} onClick={()=>selectChannel(c,ch)}><span>{ch.type==='voice'?<Volume2 size={15}/>:<Hash size={15}/>} {ch.name}</span><ArrowLeft size={14}/></button>)}</div><footer>{c.channels.length} каналов</footer></article>)}</div>}</div> : tab === 'calls' ? <div className="directory-page"><header className="directory-heading"><div><span className="eyebrow">LUNEX / СВЯЗЬ</span><h1>Звонки</h1><p>Начни голосовой или видеозвонок из личного чата.</p></div><button className="primary" onClick={()=>setNewDm(true)}><Plus size={16}/> Новый чат</button></header>{visibleDms.length===0 ? <div className="directory-empty">Пока нет личных чатов. Создай чат, чтобы позвонить.</div> : <div className="call-directory">{visibleDms.map(c=><article className="call-directory-row" key={c.id}><div className="avatar">{(c.other?.displayName||'?').slice(0,1).toUpperCase()}</div><div className="row-main"><strong>{c.other?.displayName||'Пользователь'}</strong><span>@{c.other?.username||''}</span></div><button className="secondary" onClick={()=>{setDm(c);setActiveChannel('');setTab('chats');setNotice('Чат открыт. Нажми на трубку или камеру вверху, чтобы позвонить.')}}><MessageCircle size={16}/> Открыть чат</button></article>)}</div>}</div> : tab === 'notifications' ? <div className="directory-page"><header className="directory-heading"><div><span className="eyebrow">LUNEX / ВХОДЯЩИЕ</span><h1>Уведомления</h1><p>Новые личные сообщения на этом устройстве.</p></div><button className="secondary" onClick={()=>setNotifications(prev=>prev.map(n=>({...n,read:true})))}>Отметить прочитанными</button></header>{notifications.length===0?<div className="directory-empty">Пока новых уведомлений нет. Когда придёт личное сообщение, оно появится здесь.</div>:<div className="notification-list">{notifications.map(n=><button className={\`notification-item \${n.read?'read':'unread'}\`} key={n.id} onClick={()=>{const c=dms.find(c=>c.id===n.conversationId);if(c){setDm(c);setActiveChannel('');setTab('chats');setNotifications(prev=>prev.map(item=>item.id===n.id?{...item,read:true}:item));}else setNotice('Чат больше недоступен.');}}><span className="notification-avatar"><MessageCircle size={17}/></span><span className="notification-copy"><strong>{n.user} отправил сообщение</strong><span>{n.text}</span><time>{new Date(n.time).toLocaleString()}</time></span>{!n.read&&<span className="notification-unread-dot"/>}</button>)}</div>}</div> :
      <><header className="chat-header"><button className="mobile-menu" onClick={()=>setMobileNav(!mobileNav)}><Menu/></button><div className="channel-hash">{channel?.type==='voice'?<Volume2/>:<Hash/>}</div><div className="channel-meta"><strong>{channel?.name || 'Выбери канал'}</strong><span>{community?.name || 'Сообщество'} · общий канал</span></div><div className="header-actions"><button className="icon-btn" title="Поиск чатов и каналов" onClick={()=>{setTab('chats');setDm(null);setMobileNav(true);document.querySelector('.search-box input')?.focus()}}><Search/></button><button className="icon-btn" title="Настроить оформление" onClick={()=>setSettingsOpen(true)}><Palette/></button><button className="icon-btn" title="Информация о канале" onClick={()=>setNotice(`${community?.name || 'Сообщество'} · ${channel?.name || 'Канал'} · ${community?.channels?.length || 0} каналов. Список участников пока не реализован.`)}><Users/></button></div></header>
        {channel?.type==='voice' ? <div className="chat-empty"><Headphones size={42}/><h2>{channel.name}</h2><p>Голосовой канал пока представлен как интерфейс. Передача аудио ещё не реализована.</p></div> :
        <><div className="messages" style={{backgroundImage:theme.wallpaper?`linear-gradient(#100c19aa,#100c19aa),url("${theme.wallpaper}")`:undefined}}>
          <div className="date-divider"><span>НАЧАЛО ПЕРЕПИСКИ</span></div>
          {messages.map(m=><article className={`message ${m.user===user?'own':''}`} key={m.id}><div className={`avatar ${m.system?'system':''}`}>{m.user.slice(0,1).toUpperCase()}</div><div className="message-body"><div className="message-heading"><strong>{m.user}</strong>{m.system&&<span className="bot-tag">BOT</span>}<time>{new Date(m.time).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}</time></div><div className="bubble">{m.text}</div></div></article>)}
          <div ref={bottomRef}/></div>
        <form className="composer" onSubmit={sendMessage}><button type="button" className="icon-btn attach" title="Вложения пока недоступны" onClick={()=>setNotice('Загрузка файлов пока не реализована: сервер принимает только текстовые сообщения.')}><Paperclip/></button><input value={draft} onChange={e=>setDraft(e.target.value)} placeholder={`Написать в #${channel?.name || 'канал'}…`} maxLength={4000}/><div className="emoji-wrap"><button type="button" className="icon-btn" title="Вставить эмодзи" onClick={()=>setEmojiOpen(v=>!v)}><Smile/></button>{emojiOpen && <div className="emoji-popover">{['😀','😂','🥹','😍','😎','😭','🤔','👍','❤️','🔥','🎉','👀','✨','🌙','💜','🙌'].map(emoji=><button type="button" key={emoji} onClick={()=>{setDraft(v=>v+emoji);setEmojiOpen(false)}}>{emoji}</button>)}</div>}</div><button className="send-btn" disabled={!draft.trim()} title="Отправить"><Send size={18}/></button></form></>}
      </>}
    </main>

    {settingsOpen && <div className="settings-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget)setSettingsOpen(false)}}><section className="settings-modal"><header><div><span className="eyebrow">ПЕРСОНАЛИЗАЦИЯ</span><h2>Настроить Lunex</h2><p>Меняй внешний вид под себя.</p></div><button className="icon-btn" onClick={()=>setSettingsOpen(false)}><X/></button></header>
      <div className="settings-content">
        <section className="setting-group"><h3><Palette size={17}/> Цвет акцента</h3><div className="swatches">{['#b56bff','#ff73d1','#58c7ff','#6de3a5','#ffb86b','#ff6b7a','#ffffff'].map(c=><button key={c} className={`swatch ${theme.accent===c?'picked':''}`} style={{background:c}} onClick={()=>changeTheme('accent',c)}>{theme.accent===c&&<Check size={16} color="#100c19"/>}</button>)}</div><label className="control-row">Свой цвет <input type="color" value={theme.accent} onChange={e=>changeTheme('accent',e.target.value)}/></label></section>
        <section className="setting-group"><h3><ImageIcon size={17}/> Фон и обои</h3><p>Фото применяется к текущему виду чата. Файл остаётся в браузере.</p><div className="button-row"><button className="secondary" onClick={()=>wallpaperRef.current?.click()}><ImageIcon size={16}/> Выбрать фото</button><button className="secondary" onClick={()=>changeTheme('wallpaper','')}><RotateCcw size={16}/> Убрать фон</button><input ref={wallpaperRef} hidden type="file" accept="image/*" onChange={chooseWallpaper}/></div><label className="slider-label">Прозрачность панелей <b>{theme.transparency}%</b></label><input type="range" min="25" max="100" value={theme.transparency} onChange={e=>changeTheme('transparency',Number(e.target.value))}/><label className="slider-label">Размытие <b>{theme.blur}px</b></label><input type="range" min="0" max="24" value={theme.blur} onChange={e=>changeTheme('blur',Number(e.target.value))}/></section>
        <section className="setting-group"><h3><SlidersHorizontal size={17}/> Сообщения и текст</h3><label className="slider-label">Размер текста <b>{theme.fontSize}px</b></label><input type="range" min="12" max="20" value={theme.fontSize} onChange={e=>changeTheme('fontSize',Number(e.target.value))}/><label className="slider-label">Скругление пузырей <b>{theme.bubbleRadius}px</b></label><input type="range" min="6" max="28" value={theme.bubbleRadius} onChange={e=>changeTheme('bubbleRadius',Number(e.target.value))}/><label className="control-row">Анимации интерфейса <input type="checkbox" checked={theme.animations} onChange={e=>changeTheme('animations',e.target.checked)}/></label></section>
        <section className="setting-group"><h3><Sparkles size={17}/> Темы</h3><div className="button-row"><button className="primary" onClick={exportTheme}><Download size={16}/> Экспорт темы</button><button className="secondary" onClick={()=>fileRef.current?.click()}><Upload size={16}/> Импорт темы</button><input ref={fileRef} hidden type="file" accept="application/json,.json" onChange={importTheme}/></div><button className="text-button" onClick={()=>{setTheme(DEFAULT_THEME);setNotice('Настройки темы сброшены.')}}><RotateCcw size={15}/> Сбросить настройки</button></section>
        <div className="security-note"><ShieldCheck size={18}/><span><strong>Безопасность:</strong> пароли хэшируются, но личные и общественные сообщения пока не имеют сквозного шифрования. Сервер может читать переписки. Не отправляй секретные данные. Прямой эксперимент находится отдельно в /direct.html.</span></div>
      </div>
      <footer><span>Настройки сохраняются в этом браузере.</span><button className="primary" onClick={()=>setSettingsOpen(false)}>Готово</button></footer>
    </section></div>}

    {(newCommunity || newChannel || newDm) && <div className="settings-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget){setNewCommunity(false);setNewChannel(false);setNewDm(false)}}}><form className="small-modal" onSubmit={e=>{e.preventDefault();newDm?openDm(e):newCommunity?createCommunity():createChannel()}}><button type="button" className="icon-btn close-modal" onClick={()=>{setNewCommunity(false);setNewChannel(false);setNewDm(false)}}><X/></button><h2>{newDm?'Новый личный чат':newCommunity?'Создать сообщество':'Добавить канал'}</h2><p>{newDm?'Введи логин пользователя, который уже создал аккаунт.':newCommunity?'Отдельное пространство для друзей или интересов.':'Канал появится в выбранном сообществе.'}</p>{newDm ? <><input autoFocus value={dmUsername} onChange={e=>setDmUsername(e.target.value)} placeholder="Логин пользователя" maxLength="25"/><button type="button" className="primary wide" disabled={!dmUsername.trim()} onClick={openDm}>Открыть чат</button></> : <><input autoFocus value={newName} onChange={e=>setNewName(e.target.value)} placeholder={newCommunity?'Название сообщества':'Название канала'} maxLength="48"/><button className="primary wide" disabled={!newName.trim()}>Создать</button></>}</form></div>}
    {(callState !== 'idle' || incomingCall) && <div className="call-overlay"><section className="call-card"><header><div><span className="eyebrow">LUNEX CALL</span><h2>{incomingCall ? `Входящий ${incomingCall.mode === 'video' ? 'видеозвонок' : 'звонок'}` : callMode === 'video' ? 'Видеозвонок' : 'Голосовой звонок'}</h2><p>{incomingCall?.caller?.displayName || dm?.other?.displayName || 'Собеседник'} · {callState === 'calling' ? 'Вызов…' : callState === 'connecting' ? 'Подключаемся…' : callState === 'connected' ? 'Соединено' : 'Ожидание ответа'}</p></div></header><audio ref={remoteAudioRef} autoPlay />{(callMode === 'video' || incomingCall?.mode === 'video') && <div className="call-videos">{remoteStream && <video ref={remoteVideoRef} autoPlay playsInline className="remote-video"/>}{localStream && <video ref={localVideoRef} autoPlay playsInline muted className="local-video"/>}{!remoteStream && <div className="video-placeholder">Ожидание видео собеседника…</div>}</div>}<div className="call-actions">{incomingCall ? <><button className="primary" onClick={acceptCall}><PhoneIncoming size={18}/> Принять</button><button className="danger" onClick={()=>{socket.emit('call:reject',{callId:incomingCall.callId,conversationId:incomingCall.conversationId});setIncomingCall(null);setCallState('idle')}}><PhoneOff size={18}/> Отклонить</button></> : <>{callState==='connected' && <><button className={callMuted?'secondary active-toggle':'secondary'} onClick={()=>{const next=!callMuted;localStreamRef.current?.getAudioTracks().forEach(t=>t.enabled=!next);setCallMuted(next)}}><Mic size={18}/> {callMuted?'Включить микрофон':'Выключить микрофон'}</button>{callMode==='video' && <button className="secondary" onClick={()=>{const next=!cameraOff;localStreamRef.current?.getVideoTracks().forEach(t=>t.enabled=!next);setCameraOff(next)}}><Video size={18}/> {cameraOff?'Включить камеру':'Выключить камеру'}</button>}</>}<button className="danger" onClick={()=>closeCall(true)}><PhoneOff size={18}/> Завершить</button></>}</div><p className="call-note">Звонки используют WebRTC. Если сеть не позволяет прямое соединение, может потребоваться настроенный TURN-сервер.</p></section></div>}
    {notice && <div className="toast" role="status">{notice}<button onClick={()=>setNotice('')}><X size={15}/></button></div>}
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);