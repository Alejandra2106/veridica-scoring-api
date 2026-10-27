# V12: outbox para la proyección de Query

Estado: la migración `V12__outbox_proyeccion_query.sql` está definida en el repositorio. Verifica en cada entorno si
ya fue aplicada; Scoring necesita V12 y los permisos incrementales del rol de ejecución para usar el despachador.
Scoring no modifica el esquema `consulta`.

## Qué agrega

V12 crea `public.evento_proyeccion_query`, un outbox local sin claves foráneas al esquema Query ni broker externo.
Los tipos de evento son `SOLICITANTE_PROYECTAR` y `EVALUACION_COMPLETADA`, únicos por tipo y agregado.

La tabla conserva el tipo e ID del agregado, versión y contenido JSON, estado, intentos, siguiente intento,
concesión temporal y último error. Sus estados son:

| Estado | Significado |
|---|---|
| `PENDIENTE` | Puede reclamarse al vencer `proximo_intento` y no haber una concesión vigente |
| `ENTREGADO` | Query confirmó la actualización; es terminal |
| `ERROR_PERMANENTE` | Se agotaron intentos o ocurrió un rechazo funcional; requiere revisar causa y recuperar el evento |

El despachador reclama filas con `FOR UPDATE SKIP LOCKED` en una transacción breve. La llamada HTTP se ejecuta
fuera de esa transacción, firmada con el JWT de servicio S1 y scope `proyeccion:actualizar`. En éxito marca el
evento entregado; fallos transitorios se reintentan con espera exponencial; rechazos funcionales quedan en error
permanente. Los endpoints de Query son idempotentes, de modo que un evento se puede volver a enviar si el proceso
se interrumpe después de que Query lo aceptó.

## Permisos

`docs/operacion/roles-scoring-api-v12.sql` concede `SELECT`, `INSERT` y `UPDATE`, más `USAGE` en la secuencia, al
rol de ejecución. No concede `DELETE`, `TRUNCATE` ni acceso al esquema de Query. Si V12 ya está aplicada, no se
edita ni reaplica la migración: verifica y aplica solo el script de permisos que falte.

## Arranque

1. Confirmar que V12 aparece aplicada en `scoring_flyway_schema_history` (tras la separación descrita en
   `separar-historial-flyway-scoring.md`) y que el rol de Scoring tiene permisos sobre
   `evento_proyeccion_query` y su secuencia.
2. Arrancar Query con `EVALUATION_QUERY_SAGA_HABILITADA=true` y Scoring con
   `SCORING_SAGA_HABILITADA=true` y `EVALUATION_QUERY_API_BASE_URL` apuntando a Query.
3. Configurar el mismo `SCORING_SERVICE_JWT_KEY_ID` en ambas APIs. Scoring firma con la clave privada de servicio;
   Query valida con la pública correspondiente. El token interno es distinto de los tokens de usuario de Auth.
4. El catálogo de recomendaciones en Query debe estar aprobado para que el flujo completo de evaluación termine.

## Diagnóstico de eventos

La consulta siguiente es de solo lectura y permite comprobar si Query recibió las altas y confirmaciones:

```sql
SELECT id_evento, tipo_evento, id_agregado, estado, intentos,
       codigo_ultimo_error, ultimo_error, proximo_intento
  FROM public.evento_proyeccion_query
 ORDER BY id_evento DESC;
```

`PENDIENTE` indica entrega en curso o reintento programado; `ENTREGADO` confirma una respuesta 2xx de Query;
`ERROR_PERMANENTE` requiere corregir la causa antes de recuperar el evento. No borrar ni truncar esta tabla para
limpiar pendientes: conserva cambios que aún no llegaron a la proyección.

Las migraciones nuevas se aplican mediante el procedimiento controlado de
`docs/operacion/despliegue-v10-v11.md`; no actives la bandera Flyway de migración durante el arranque normal.
