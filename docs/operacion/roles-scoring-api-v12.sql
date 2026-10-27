-- Permisos incrementales del outbox de proyección agregado por V12.
-- Ejecutar DESPUÉS de aplicar V12, con un administrador de la base, sobre motor_scoring.
-- No crea roles ni modifica permisos de otras tablas.
-- Uso: psql -h <host> -U <administrador> -d motor_scoring \
--   -v rol_app=<rol de ejecución de scoring-api> -v base_esperada=motor_scoring \
--   -f roles-scoring-api-v12.sql

\set ON_ERROR_STOP on

\if :{?rol_app}
\else
    \echo 'Falta -v rol_app=<rol de ejecución de scoring-api>'
    \quit
\endif
\if :{?base_esperada}
\else
    \set base_esperada motor_scoring
\endif

SELECT current_database() = :'base_esperada' AS base_correcta \gset
\if :base_correcta
\else
    \echo 'Conectado a una base distinta de la esperada; no se hace nada'
    \quit
\endif

BEGIN;
GRANT SELECT, INSERT, UPDATE ON public.evento_proyeccion_query TO :"rol_app";
GRANT USAGE ON SEQUENCE public.evento_proyeccion_query_id_evento_seq TO :"rol_app";
COMMIT;
