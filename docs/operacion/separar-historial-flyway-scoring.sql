-- Separa el historial de scoring-api sin modificar el historial compartido ni los datos de Auth.
-- Requiere respaldo verificado y APIs detenidas. Ejecutar como propietario/administrador en la base correcta.
-- Después configurar SCORING_FLYWAY_TABLE=scoring_flyway_schema_history.
\set ON_ERROR_STOP on

\if :{?base_esperada}
\else
    \set base_esperada CreditsCore
\endif

SELECT current_database() = :'base_esperada' AS base_correcta \gset
\if :base_correcta
\else
    \echo 'Base incorrecta: no se modifica ningún historial'
    \quit
\endif

BEGIN;

LOCK TABLE public.flyway_schema_history IN SHARE MODE;

DO $$
BEGIN
    IF to_regclass('public.scoring_flyway_schema_history') IS NOT NULL THEN
        RAISE EXCEPTION 'public.scoring_flyway_schema_history ya existe; inspección manual requerida';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM public.flyway_schema_history
         WHERE script IN (
            'V1__create_solicitante_table.sql',
            'V2__create_riesgo_table.sql',
            'V3__update_riesgo_estado.sql',
            'V4__create_regla_scoring_table.sql',
            'V5__create_evaluacion_tables.sql',
            'V6__create_politica_clasificacion_tables.sql',
            'V7__add_autor_politica_clasificacion.sql',
            'V8__add_clasificacion_evaluacion.sql',
            'V9__proteger_inmutabilidad_politica_clasificacion.sql',
            'V10__indices_consultas_evaluacion.sql',
            'V11__saga_recomendaciones.sql',
            'V12__outbox_proyeccion_query.sql'
         ) AND success
    ) THEN
        RAISE EXCEPTION 'No hay migraciones de Scoring exitosas en el historial compartido; no se puede inferir un baseline seguro';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.flyway_schema_history
         WHERE script IN (
            'V1__create_solicitante_table.sql',
            'V2__create_riesgo_table.sql',
            'V3__update_riesgo_estado.sql',
            'V4__create_regla_scoring_table.sql',
            'V5__create_evaluacion_tables.sql',
            'V6__create_politica_clasificacion_tables.sql',
            'V7__add_autor_politica_clasificacion.sql',
            'V8__add_clasificacion_evaluacion.sql',
            'V9__proteger_inmutabilidad_politica_clasificacion.sql',
            'V10__indices_consultas_evaluacion.sql',
            'V11__saga_recomendaciones.sql',
            'V12__outbox_proyeccion_query.sql'
         ) AND NOT success
    ) THEN
        RAISE EXCEPTION 'Hay migraciones fallidas de Scoring en el historial compartido; revisar antes de separar';
    END IF;
END
$$;

CREATE TABLE public.scoring_flyway_schema_history
    (LIKE public.flyway_schema_history INCLUDING ALL);

INSERT INTO public.scoring_flyway_schema_history
SELECT *
  FROM public.flyway_schema_history
 WHERE script IN (
    'V1__create_solicitante_table.sql',
    'V2__create_riesgo_table.sql',
    'V3__update_riesgo_estado.sql',
    'V4__create_regla_scoring_table.sql',
    'V5__create_evaluacion_tables.sql',
    'V6__create_politica_clasificacion_tables.sql',
    'V7__add_autor_politica_clasificacion.sql',
    'V8__add_clasificacion_evaluacion.sql',
    'V9__proteger_inmutabilidad_politica_clasificacion.sql',
    'V10__indices_consultas_evaluacion.sql',
    'V11__saga_recomendaciones.sql',
    'V12__outbox_proyeccion_query.sql'
 ) AND success;

COMMIT;

SELECT version, description, script, checksum, success
  FROM public.scoring_flyway_schema_history
 ORDER BY installed_rank;
