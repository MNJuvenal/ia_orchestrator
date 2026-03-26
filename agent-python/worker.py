import os
import json
import urllib.request
import urllib.parse
import time

import pika
import ollama


def fetch_history_http(session_id: str, limit: int = 20):
    try:
        qs = urllib.parse.urlencode({'limit': str(limit)})
        url = f"http://localhost:8080/api/ai/history/{urllib.parse.quote(session_id)}?{qs}"
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = resp.read()
            arr = json.loads(data)
            # expected list of {role, content}
            return arr if isinstance(arr, list) else []
    except Exception as e:
        print(f"[HTTP] fetch_history failed: {e}")
        return []


def extract_session_id_from_props(props):
    # try headers.session_id, then correlation_id, then 'anon'
    sid = None
    try:
        headers = getattr(props, 'headers', None)
        if headers and isinstance(headers, dict):
            sid = headers.get('session_id') or headers.get(b'session_id')
            if isinstance(sid, bytes):
                sid = sid.decode()
    except Exception:
        sid = None
    if not sid:
        sid = getattr(props, 'correlation_id', None)
    if not sid:
        sid = 'anon'
    return str(sid)


connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()
channel.queue_declare(queue='ai_tasks_queue', durable=True)


def on_request(ch, method, props, body):
    prompt = body.decode()
    session_id = extract_session_id_from_props(props)
    print(f" [Recu] session={session_id} prompt={prompt}")

    # Fetch recent history from orchestrator (Postgres) and append current prompt
    try:
        history = fetch_history_http(session_id, limit=30)
        messages = history + [{ 'role': 'user', 'content': prompt }]
    except Exception as e:
        print(f"[HTTP] fetch history failed: {e}")
        messages = [{ 'role': 'user', 'content': prompt }]

    # Call the IA
    try:
        response = ollama.chat(model='tinyllama', messages=messages)
        answer = response['message']['content']
    except Exception as e:
        print(f"[Model] call failed: {e}")
        answer = "Désolé, l'IA n'est pas disponible pour le moment."

    # Note: persistence of assistant response is handled by the orchestrator (ResponsesListener)

    # Reply via RabbitMQ
    try:
        # Publish async result to ai_responses queue with job_id header if provided
        headers = {}
        h = getattr(props, 'headers', {}) or {}
        job_id = None
        try:
            job_id = h.get('job_id') or h.get(b'job_id')
            if isinstance(job_id, bytes): job_id = job_id.decode()
            if job_id:
                headers['job_id'] = job_id
        except Exception:
            job_id = None

        # Also include session_id header
        try:
            sid = h.get('session_id') or h.get(b'session_id')
            if isinstance(sid, bytes): sid = sid.decode()
            if sid:
                headers['session_id'] = sid
        except Exception:
            pass

        ch.basic_publish(
            exchange='',
            routing_key='ai_responses',
            properties=pika.BasicProperties(headers=headers),
            body=answer
        )
    except Exception as e:
        print(f"[RabbitMQ] publish failed: {e}")

    ch.basic_ack(delivery_tag=method.delivery_tag)
    print(f" [Réponse envoyée] session={session_id}")


# On traite un seul message à la fois (plus pro)
channel.basic_qos(prefetch_count=1)
channel.basic_consume(queue='ai_tasks_queue', on_message_callback=on_request)

print(" [X] Worker RPC prêt (Postgres as single source of truth; no local SQLite writes)")
channel.start_consuming()