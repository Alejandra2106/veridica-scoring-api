# Pendientes tras el despliegue del 2026-10-19

Estado de `motor_scoring`: `public` en V11, `consulta` en V1, roles de ejecución creados y saga **deshabilitada**
(registro en `despliegue-v10-v11.md`). Ningún secreto se escribe en el repositorio ni se muestra.

## 1. Credenciales de los roles de ejecución (las fija la usuaria)

Decisión del 2026-10-19: los secretos los crea la usuaria con los scripts de `docs/operacion/secretos/`; nadie
más ve los valores. Probados contra `motor_scoring_it` con roles y carpeta temporales (7 de 7 comprobaciones).

1. En una terminal propia de PowerShell, con `PGPASSWORD` del administrador de la base:

   ```powershell
   cd scoring-api\docs\operacion\secretos
   .\configurar-secretos.ps1 -ClavePublicaAuth 'C:uta\la\clave-publica-de-auth-api.pem'
   ```

   Pide dos veces cada contraseña (`scoring_app` y `consulta_app`, mínimo 16 caracteres) y:
   - la fija en `motor_scoring` como verificador SCRAM-SHA-256 calculado en el equipo, igual que `\password`
     (el servidor nunca recibe la contraseña en claro);
   - guarda una copia cifrada con DPAPI en `%USERPROFILE%\.motor-scoring` (solo tu usuario puede descifrarla;
     carpeta con permisos solo para tu usuario);
   - genera el par RSA de la saga (sección 2) y escribe `configuracion.json` (sin secretos).
2. `auth-api` no cambia: ya usa `auth_api` y `auth_migrator`.
3. Las migraciones futuras siguen exigiendo `*_FLYWAY_MIGRAR_AL_ARRANCAR=true` con el rol propietario y respaldo.

## 2. Claves de la saga y habilitación

`configurar-secretos.ps1` genera el par RSA con el `kid` `saga-AAAAMM` en `%USERPROFILE%\.motor-scoring\saga`
(la privada solo la usa `scoring-api`). Para arrancar con la saga habilitada:

```powershell
.\iniciar-api.ps1 -Api consulta -Jar ..\..\..\..\evaluation-query-api	arget\evaluation-query-api-0.0.1-SNAPSHOT.jar -Saga
.\iniciar-api.ps1 -Api scoring  -Jar ..\..\..	arget\motor-scoring-crediticio-0.0.1-SNAPSHOT.jar -Saga
```

El lanzador pasa las variables (rol, contraseña descifrada, claves y `kid`) solo al proceso de la API, sin
heredar las variables de administrador de la sesión; `-SoloComprobar` verifica la conexión sin arrancar.
Al habilitar la saga, el reenvío completa las evaluaciones que hayan quedado PENDIENTE; las que hayan agotado
sus intentos requieren `reactivar_trabajo_recomendacion`. Pendiente operativo: quién custodia la clave privada
y cada cuánto se rota (para rotar, `-IdClaveSaga` con un `kid` nuevo).

## 3. Política de clasificación y reglas de scoring

- **Política (decisión del 2026-10-19):** se publicarán los rangos del ejemplo de HU11. La publica la usuaria como
  `ADMINISTRADOR_RIESGO`, con su token de `auth-api` (Swagger de `scoring-api` con el perfil `dev`, o):

  ```text
  POST /api/v1/politicas-clasificacion
  Authorization: Bearer <token del administrador>
  Content-Type: application/json

  {"rangos": [
    {"categoria": "BAJO",  "limiteInferior": -100, "limiteSuperior": 29},
    {"categoria": "MEDIO", "limiteInferior": 30,   "limiteSuperior": 79},
    {"categoria": "ALTO",  "limiteInferior": 80,   "limiteSuperior": 130}
  ]}
  ```

  Respuesta esperada: 201 con la versión 1. Hasta entonces, toda evaluación responde 409
  `POLITICA_VIGENTE_NO_DISPONIBLE`.
- **Reglas (decisión del 2026-10-19: se dejan como están por ahora):** las 42 reglas activas de `motor_scoring`
  son datos de pruebas (todas con puntaje positivo, muchas duplicadas o imposibles; suman hasta 705 puntos).
  Con la política publicada, las evaluaciones cuyo puntaje supere 130 responderán 409
  `PUNTAJE_SIN_CLASIFICACION`. La API no permite desactivar reglas sueltas (solo editarlas o desactivar una
  variable entera); limpiar las reglas o añadir esa capacidad requiere una decisión y, en su caso, una HU.

## 4. HU14: criterios y decisiones

| Elemento | Estado |
|---|---|
| P-HU14-01 catálogo y P-HU14-02 factor desfavorable | Resueltas el 2026-10-19 |
| P-HU14-03 (409 si la evaluación está PENDIENTE), P-HU14-04 (lista vacía con `generadas: false` para evaluaciones anteriores a la saga), P-HU14-05 (rutas), P-HU14-08 (códigos de error) | **Confirmadas por María Alejandra el 2026-10-19**, tal como están implementadas |
| CA17 / P-HU14-06 (esquema `consulta` y sus dos tablas) | Implementado y desplegado; **la justificación sigue sin aprobar** |
| CA01 y CA13 en producción | Verificados en ensayo; efectivos en `motor_scoring` cuando se habilite la saga (sección 2) |
| CA12 (2 s) | Medido en un entorno aislado (p95 ≤ 35 ms con unas 1.400 evaluaciones); falta medirlo en el servidor definitivo |
| CA14 y CA16 (operación) | Falta designar quién vigila los trabajos en ERROR_PERMANENTE y ejecuta la reactivación |
| P-HU10-04 (ventana de 15 min frente a la tolerancia de reloj de 60 s) | Sin decisión |

Detalle de cada decisión: `evaluation-query-api/docs/quality/HU14-decisiones-pendientes.md`.

## 5. Credencial expuesta en el historial público

- Un commit integrado en `main` mediante el PR #16 (octubre de 2026) añadió una contraseña de base de datos como
  valor por defecto de `DB_PASSWORD` en `application.properties`; un commit posterior del mismo PR la retiró. La
  versión actual de todas las ramas está limpia, pero el valor sigue accesible en el historial público del
  repositorio. No coincide con la credencial de `motor_scoring` usada en el despliegue del 2026-10-19.
- Riesgo: cualquiera con acceso al repositorio puede recuperarla. Borrarla del historial exige reescribir `main`
  y forzar su publicación, lo que afecta a todo el equipo y no elimina copias ya descargadas.
- Acción pendiente (responsable de esa credencial): cambiar esa contraseña donde se use y revisar los accesos;
  decidir en equipo si además se reescribe el historial. No se ha reescrito nada.
- Revisión del 2026-10-19: no hay más secretos, claves privadas, tokens ni archivos `.env` en el historial de
  `scoring-api`, `auth-api` ni `evaluation-query-api` (todas las ramas, remotas y stash).
