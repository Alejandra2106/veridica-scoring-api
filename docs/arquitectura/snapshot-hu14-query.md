# Snapshot HU14 hacia Query

## Alcance

La saga de recomendaciones de Scoring envía a `evaluation-query-api` la evaluación `PENDIENTE` y sus factores ya
persistidos. Scoring conserva la propiedad de evaluar reglas y clasificar; Query conserva la propiedad del catálogo,
la selección de recomendaciones y su almacenamiento. El transporte reutiliza la llamada HTTP HU14 y los reintentos
existentes, sin agregar una API, colas ni broker.

## Contrato

`PUT /internal/v1/evaluaciones/{idEvaluacion}/recomendaciones` recibe como JSON un `SnapshotEvaluacionQuery`:

```json
{
  "idEvaluacion": 101,
  "idSolicitante": 42,
  "fechaEvaluacion": "2026-10-22T10:15:30",
  "scoreTotal": -20,
  "categoria": "MEDIO",
  "versionPolitica": 1,
  "estado": "PENDIENTE",
  "factores": [
    {
      "orden": 1,
      "idDetalleOrigen": 501,
      "idRegla": 7,
      "variable": "NUMERO_MORAS",
      "operadorAplicado": ">",
      "valorCondicionAplicado": "2",
      "puntajeReglaAplicado": -20,
      "condicionCumplida": true,
      "contribucion": -20
    }
  ]
}
```

El adaptador JDBC lee `evaluacion`, `politica_clasificacion`, `detalle_evaluacion`, `regla_scoring` y `riesgo` en
una sola búsqueda lógica. `orden` se deriva del ID persistido de detalle para que el snapshot sea estable. No se
recalculan puntajes ni se copian atributos personales o financieros.

Query valida la forma del snapshot, inserta primero el solicitante local y luego la cabecera y los factores en su
esquema `consulta`. La misma transacción genera las recomendaciones desde el snapshot recibido. Una repetición
con contenido idéntico es idempotente; contenido distinto para el mismo ID se rechaza sin sobrescribir historia.

## Despliegue gradual

Desplegar Query compatible con snapshot antes de Scoring que envía el cuerpo. Query conserva temporalmente el
camino de compatibilidad para peticiones sin cuerpo, que consulta `public`; retirarlo requiere confirmar que no
quedan instancias antiguas de Scoring. Antes de activar el flujo con snapshot, aplicar la migración V2 de Query y
otorgar al rol de Query los permisos INSERT/SELECT documentados para las tablas `consulta`.

Auth no participa en este cambio. Scoring firma el token S1 con su identidad de servicio y Query valida la clave
pública configurada, emisor, audiencia y scope `recomendaciones:generar`; la API Auth no se modifica.
