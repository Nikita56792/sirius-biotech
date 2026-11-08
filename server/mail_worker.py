"""One durable invitation worker. No public SMTP listener or open relay."""
import os
import signal
import sqlite3
import time
from contextlib import contextmanager
import mail_service

DB = os.environ.get('DATABASE_PATH','/data/sbs.sqlite3')
running = True


def stop(*_):
    global running
    running = False


@contextmanager
def connect():
    c = sqlite3.connect(DB,timeout=15)
    c.row_factory = sqlite3.Row
    try:
        yield c
        c.commit()
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    while running:
        try:
            worked = mail_service.process_one(connect)
            with open('/tmp/mail-worker-heartbeat','w') as heartbeat:
                heartbeat.write(str(time.time()))
        except Exception:
            # Never log an exception containing a recipient, code, or SMTP secret.
            print('Mail worker cycle failed; retrying.',flush=True)
            worked = False
        if not worked:
            time.sleep(3)
