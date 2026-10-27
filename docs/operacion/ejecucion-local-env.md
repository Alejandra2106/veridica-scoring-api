# Ejecución local de scoring-api

Requisitos: JDK 21, PostgreSQL y clave pública PEM de Auth. La API corre en `http://localhost:8080`.

1. Desde PowerShell en la raíz, ejecuta `Copy-Item .env.example .env`. Completa DB_URL, DB_USERNAME, DB_PASSWORD y la ruta a la clave pública de Auth. El ejemplo asume que los tres repos están como carpetas hermanas `auth`, `scoring-api` y `evaluation-query-api`; si no, ajusta la ruta. No copies aquí la privada de Auth.
2. La BD debe existir y contener las migraciones requeridas. Por seguridad Flyway solo valida por defecto (`SCORING_FLYWAY_MIGRAR_AL_ARRANCAR=false`). Las migraciones se ejecutan de forma controlada según `docs/operacion/despliegue-v10-v11.md`; no habilites migración automática en cada arranque ni ejecutes sobre una BD con datos sin respaldo.
3. Ejecuta `.\run-local.ps1` (Windows PowerShell) para cargar `.env` en el proceso Maven y ejecutar `.\mvnw.cmd spring-boot:run`. El script restaura las variables de la terminal al terminar.
4. Para arrancar solo Scoring deja `SCORING_SAGA_HABILITADA=false`. En ese modo no habrá finalización de recomendaciones de nuevas evaluaciones hasta habilitar la integración con Query.

## Flujo integrado de la saga

La saga requiere Scoring y Query activos, catálogo aprobado, y `true` en `SCORING_SAGA_HABILITADA` y `EVALUATION_QUERY_SAGA_HABILITADA`. Genera un par RSA exclusivo de servicio; no reutilices las claves de Auth:

```powershell
New-Item -ItemType Directory -Force keys-service | Out-Null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys-service/scoring-service-private.pem
openssl pkey -in keys-service/scoring-service-private.pem -pubout -out keys-service/scoring-service-public.pem
```

Configura el mismo `SCORING_SERVICE_JWT_KEY_ID` en ambas APIs. Scoring usa la privada y la pública; Query usa solo la pública; en el layout de carpetas hermanas su `.env` apunta directamente a `../scoring-api/keys-service/scoring-service-public.pem`. En despliegues separados, distribuye solo ese PEM público de forma segura. La privada no sale de Scoring. No uses `SCORING_FLYWAY_MIGRAR_AL_ARRANCAR=true` sin seguir el procedimiento de despliegue.
