FROM python:3.12.10-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && groupadd -g 10001 sbs && useradd -u 10001 -g sbs sbs && mkdir /data && chown sbs:sbs /data
COPY --chown=sbs:sbs main.py mail_service.py mail_worker.py ./
COPY --chown=sbs:sbs mail ./mail
COPY --chown=sbs:sbs static ./static
COPY --chown=sbs:sbs fixtures ./fixtures
USER sbs
EXPOSE 8080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-proxy-headers", "--no-access-log"]
