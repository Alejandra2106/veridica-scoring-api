# Separar el historial Flyway de Scoring

## Motivo

La base compartida tiene un historial Flyway en `public` que llegó a V111, mientras Scoring solo distribuye
migraciones hasta V12. Flyway compara ese historial global con las migraciones de Scoring y avisa que la base
está en una versión superior. La advertencia por sí sola no indica que falten tablas, pero mezcla el ciclo de
migraciones de módulos/APIs independientes y puede ocultar migraciones pendientes.

Query ya administra su historial en su esquema `consulta`. Scoring puede mantener sus objetos en `public` y
usar allí `scoring_flyway_schema_history`. Este cambio no requiere ni modifica `auth-api`, sus tablas ni su
historial existente.

## Adopción en una base existente

La separación debe ser una operación controlada. **No arranques Scoring con un nombre de historial nuevo antes
de copiar sus registros aplicados**: Flyway podría intentar crear tablas que ya existen. El SQL de adopción
solo copia las filas exitosas cuyo nombre de migración coincide exactamente con las migraciones de Scoring; no
cambia ni borra filas del historial original.

1. Detén las tres APIs y confirma que no haya sesiones desplegando migraciones.
2. Crea y verifica un respaldo de la base.
3. Inspecciona el historial existente en modo lectura:

   ```sql
   SELECT installed_rank, version, description, script, success
     FROM public.flyway_schema_history
    ORDER BY installed_rank;
   ```

   Confirma que las filas de Scoring usan los nombres listados en
   `separar-historial-flyway-scoring.sql`. Si no coinciden, hay versiones fallidas o no aparece ninguna fila de
   Scoring, detente y resuelve el historial manualmente con un plan basado en el estado real de la base.
4. Ejecuta el script con una cuenta propietaria/administradora y la base esperada correcta:

   ```powershell
   psql -h 127.0.0.1 -U <administrador> -d CreditsCore -v base_esperada=CreditsCore -f docs/operacion/separar-historial-flyway-scoring.sql
   ```

   El script falla y revierte si ya existe el historial destino, no encuentra migraciones exitosas de Scoring o
   encuentra una fila fallida. Revisa la lista de salida y compárala con las migraciones efectivamente aplicadas.
5. En la terminal desde donde se iniciará Scoring, selecciona el historial separado:

   ```powershell
   $env:SCORING_FLYWAY_TABLE = 'scoring_flyway_schema_history'
   $env:SCORING_FLYWAY_MIGRAR_AL_ARRANCAR = 'false'
   ```

   Inicia Scoring y valida que Flyway solo informe migraciones conocidas para Scoring. No habilites migración
   automática si aparecen migraciones pendientes no previstas; verifica primero la secuencia y el estado físico
   de los objetos. Una migración pendiente se aplica solo mediante el procedimiento controlado de despliegue.
6. Concede `SELECT` sobre el nuevo historial al rol de ejecución. Para el rol documentado `scoring_app`,
   ejecútalo como propietario/administrador:

   ```sql
   GRANT SELECT ON public.scoring_flyway_schema_history TO scoring_app;
   ```

   Sustituye `scoring_app` si el entorno usa otro nombre. El script de aprovisionamiento
   `roles-scoring-api.sql` también concede este permiso en bases nuevas.

El valor por defecto de `SCORING_FLYWAY_TABLE` se mantiene como `flyway_schema_history` para no cambiar
silenciosamente el comportamiento de bases ya desplegadas. En bases nuevas, configura
`SCORING_FLYWAY_TABLE=scoring_flyway_schema_history` antes del primer arranque; Flyway creará el historial
propio y aplicará las migraciones normalmente.

## Corrección del binding JWT de servicio

`ServicioJwtProperties` es un `record` con un constructor adicional de compatibilidad. Su constructor canónico
está marcado con `@ConstructorBinding` para que Spring Boot enlace `scoring.servicio-jwt.*` al iniciar la Saga.
La anotación solo determina el binding; no cambia valores ni genera claves. El servicio conserva su clave
privada exclusivamente en Scoring y valida los tokens de usuario con la clave pública de Auth como antes.
