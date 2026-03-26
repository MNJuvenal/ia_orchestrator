window.app = (function(){
    function getHistoryRange(){
        try{ return parseInt(localStorage.getItem('historyRange')||'10'); }catch(e){ return 10; }
    }
    function setHistoryRange(n){
        localStorage.setItem('historyRange', String(n));
    }
    function incrementMetrics(ms){
        try{
            const raw = localStorage.getItem('apiMetrics')||'{"count":0,"totalMs":0}';
            const m = JSON.parse(raw);
            m.count = (m.count||0)+1;
            m.totalMs = (m.totalMs||0)+Number(ms||0);
            localStorage.setItem('apiMetrics', JSON.stringify(m));
        }catch(e){ console.warn('app.incrementMetrics error', e); }
    }
    function getMetrics(){
        try{ return JSON.parse(localStorage.getItem('apiMetrics')||'{"count":0,"totalMs":0}'); }catch(e){ return {count:0,totalMs:0}; }
    }
    function getSessionId(){
        try{
            let sid = localStorage.getItem('sessionId');
            if(!sid){
                sid = cryptoRandomId();
                localStorage.setItem('sessionId', sid);
            }
            return sid;
        }catch(e){ return 'anon'; }
    }
    function setSessionId(sid){ try{ localStorage.setItem('sessionId', sid); }catch(e){} }

    function cryptoRandomId(){
        // simple random id using crypto API
        if(window.crypto && crypto.randomUUID) return crypto.randomUUID();
        const arr = new Uint8Array(16);
        crypto.getRandomValues(arr);
        return Array.from(arr).map(b=>b.toString(16).padStart(2,'0')).join('');
    }

    function scrollToBottom(){
        try{ const el = document.getElementById('chatContainer'); if(el) { el.scrollTop = el.scrollHeight; } }catch(e){}
    }

    // Start an EventSource for server-sent events for a session and forward events to the provided .NET object reference
    function startEventSource(sessionId, dotNetRef) {
        try {
            if (!sessionId) return null;
            const url = '/api/ai/stream/' + encodeURIComponent(sessionId);
            const es = new EventSource(url);
            // handle named event 'ai-response'
            es.addEventListener('ai-response', function(e) {
                try {
                    const data = JSON.parse(e.data);
                    if (dotNetRef) dotNetRef.invokeMethodAsync('OnSseMessage', data.jobId, data.result);
                } catch (err) { console.error('SSE ai-response parse error', err, e.data); }
            });
            // fallback for unnamed 'message' events
            es.onmessage = function (e) {
                try {
                    const data = JSON.parse(e.data);
                    if (dotNetRef) dotNetRef.invokeMethodAsync('OnSseMessage', data.jobId, data.result);
                } catch (err) { console.error('SSE message parse error', err, e.data); }
            };
            es.onerror = function (err) {
                console.warn('SSE error', err);
            };
            // store reference so we can close later if needed
            window._ia_eventsource = es;
            return true;
        } catch (e) {
            console.error('startEventSource failed', e);
            return false;
        }
    }

    function stopEventSource(){ try{ if(window._ia_eventsource) { window._ia_eventsource.close(); window._ia_eventsource = null; } }catch(e){}
    }
    return { getHistoryRange, setHistoryRange, incrementMetrics, getMetrics, getSessionId, setSessionId, scrollToBottom, startEventSource, stopEventSource };
})();
