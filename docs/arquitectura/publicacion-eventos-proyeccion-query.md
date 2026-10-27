# Entrega transaccional de eventos a la proyección de Query

## Alcance

Scoring registra dos tipos de evento en `public.evento_proyeccion_query` (V12): alta de solicitante y finalización
de evaluación. El despachador programado reclama esos eventos y los entrega a las operaciones internas de Query.
No se usa un broker ni se modifica directamente el esquema `consulta` desde Scoring.

## Límites modulares

El módulo `applicants` depende del puerto de salida `RegistrarAltaSolicitanteEnProyeccionPort`; su servicio de
aplicación no conoce SQL ni la API Query. El módulo `evaluations` inserta el evento de finalización desde el
adaptador que confirma la saga HU14. El módulo `projection` contiene el caso de uso de entrega, su outbox JDBC y
el adaptador HTTP; la llamada remota ocurre fuera de la transacción de reclamo.

## Consistencia y recuperación

- El alta de solicitante y su evento usan la misma transacción Spring. Un fallo al insertar el evento revierte
  también el alta.
- `EVALUACION_COMPLETADA` se inserta en la misma transacción que marca el trabajo y la evaluación como completos.
- Las inserciones son idempotentes por `(tipo_evento, id_agregado)`. El evento no copia datos personales ni
  factores de evaluación.
- El despachador reclama con `FOR UPDATE SKIP LOCKED`, asigna una concesión temporal y aumenta intentos. Si cae,
  otra instancia puede reclamar el evento tras vencer la concesión.
- La publicación usa un JWT S1 RS256 de vida corta firmado por Scoring con scope `proyeccion:actualizar`.
  Query valida `kid`, emisor, audiencia, sujeto, tipo, vigencia y scope con la clave pública correspondiente.
  Este token interno no es un token de usuario de Auth.
- Los fallos transitorios se reintentan con la política exponencial existente. Rechazos funcionales y reintentos
  agotados quedan `ERROR_PERMANENTE`; el log identifica el evento y el código. Las rutas internas de Query son
  idempotentes para tolerar un reenvío si Scoring cae después de la aceptación remota.
- La proyección de Query mantiene una evaluación en `PENDIENTE` hasta recibir la confirmación. El frontend reintenta
  temporalmente sus lecturas y permite repetirlas manualmente si la proyección sigue pendiente.

## Operación y diagnóstico

El despachador se activa con `SCORING_SAGA_HABILITADA=true`; Query debe usar
`EVALUATION_QUERY_SAGA_HABILITADA=true`. Ambos servicios deben compartir `SCORING_SERVICE_JWT_KEY_ID`, y la clave
pública de Query debe corresponder a la privada de Scoring. Se requieren V12 y los permisos descritos en
`docs/operacion/despliegue-v12-proyeccion-query.md`.

Consulta de solo lectura para revisar el estado del outbox:

```sql
SELECT id_evento, tipo_evento, id_agregado, estado, intentos,
       codigo_ultimo_error, ultimo_error, proximo_intento
  FROM public.evento_proyeccion_query
 ORDER BY id_evento DESC;
```

`PENDIENTE` indica que la entrega está en curso o reprogramada; `ENTREGADO` confirma una respuesta 2xx de Query;
`ERROR_PERMANENTE` requiere corregir la causa y una recuperación controlada. No borrar ni truncar la tabla para
limpiar pendientes.