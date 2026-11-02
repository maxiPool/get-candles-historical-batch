#!/usr/bin/env python3
"""Send a monitoring email with the batch's own SMTP settings (spring.mail.* in application-local.yml).

Used by bin/cron-wrapper.sh and bin/freshness-check.py, so alerts still go out when the Java app cannot
start. Usage: notify_email.py --subject "..." [--body-file FILE]   (body from stdin otherwise)
Config: $CANDLES_MAIL_CONFIG, default <repo>/src/main/resources/application-local.yml.
"""
import argparse
import os
import smtplib
import sys
from email.message import EmailMessage

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CONFIG = os.path.join(REPO, "src", "main", "resources", "application-local.yml")
SUBJECT_PREFIX = "MONITORING-GetCandlesBatch"


def load_mail_config(path):
    with open(path) as f:
        doc = yaml.safe_load(f) or {}
    spring = doc.get("spring") or {}
    mail = dict(spring.get("mail") or {})
    mail.update(doc.get("spring.mail") or {})
    missing = [k for k in ("username", "password", "recipient") if not mail.get(k)]
    if missing:
        raise ValueError("spring.mail is missing %s in %s" % (", ".join(missing), path))
    return {
        "username": str(mail["username"]),
        "password": str(mail["password"]),
        "recipient": str(mail["recipient"]),
        "host": str(mail.get("host", "smtp.gmail.com")),
        "port": int(mail.get("port", 587)),
    }


def build_message(cfg, subject, body):
    msg = EmailMessage()
    msg["From"] = cfg["username"]
    msg["To"] = cfg["recipient"]
    msg["Subject"] = subject if subject.startswith(SUBJECT_PREFIX) else "%s: %s" % (SUBJECT_PREFIX, subject)
    msg.set_content(body)
    return msg


def send(cfg, msg, smtp_factory=smtplib.SMTP):
    with smtp_factory(cfg["host"], cfg["port"], timeout=30) as s:
        s.starttls()
        s.login(cfg["username"], cfg["password"])
        s.send_message(msg)


def notify(subject, body, config_path=None, smtp_factory=smtplib.SMTP):
    cfg = load_mail_config(config_path or os.environ.get("CANDLES_MAIL_CONFIG", DEFAULT_CONFIG))
    send(cfg, build_message(cfg, subject, body), smtp_factory)


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--subject", required=True)
    p.add_argument("--body-file")
    a = p.parse_args(argv)
    if a.body_file:
        with open(a.body_file) as f:
            body = f.read()
    else:
        body = sys.stdin.read()
    try:
        notify(a.subject, body)
    except Exception as e:  # never echo the config: it holds the SMTP password
        print("notify_email: FAILED to send alert: %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
