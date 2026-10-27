package com.crediticio.evaluations.infrastructure.output;

import com.crediticio.evaluations.ports.output.SnapshotEvaluacionQuery;
import com.crediticio.evaluations.ports.output.SnapshotEvaluacionQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Lee el snapshot ya persistido en Scoring; no recalcula puntajes ni vuelve a evaluar reglas. */
@Component
public class SnapshotEvaluacionQueryJdbcAdapter implements SnapshotEvaluacionQueryPort {

    private static final String CONSULTAR_EVALUACION = """
            SELECT e.id_evaluacion, e.id_solicitante, e.fecha_evaluacion, e.score_total, e.categoria,
                   p.version AS version_politica, e.estado
            FROM evaluacion e
            JOIN politica_clasificacion p ON p.id_politica = e.id_politica
            WHERE e.id_evaluacion = :idEvaluacion
            """;
    private static final String CONSULTAR_FACTORES = """
            SELECT d.id_detalle_evaluacion, d.id_regla, r.variable, d.operador_aplicado,
                   d.valor_condicion_aplicado, d.puntaje_regla_aplicado, d.condicion_cumplida,
                   d.puntaje_obtenido
            FROM detalle_evaluacion d
            JOIN regla_scoring rs ON rs.id_regla = d.id_regla
            JOIN riesgo r ON r.id_riesgo = rs.id_riesgo
            WHERE d.id_evaluacion = :idEvaluacion
            ORDER BY d.id_detalle_evaluacion
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SnapshotEvaluacionQueryJdbcAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<SnapshotEvaluacionQuery> buscar(long idEvaluacion) {
        MapSqlParameterSource parametros = new MapSqlParameterSource("idEvaluacion", idEvaluacion);
        List<Cabecera> cabeceras = jdbcTemplate.query(CONSULTAR_EVALUACION, parametros,
                (fila, numero) -> new Cabecera(fila.getLong("id_evaluacion"), fila.getLong("id_solicitante"),
                        fila.getTimestamp("fecha_evaluacion").toLocalDateTime(), fila.getInt("score_total"),
                        fila.getString("categoria"), fila.getInt("version_politica"), fila.getString("estado")));
        if (cabeceras.isEmpty()) {
            return Optional.empty();
        }
        List<SnapshotEvaluacionQuery.Factor> factores = jdbcTemplate.query(CONSULTAR_FACTORES, parametros,
                (fila, numero) -> new SnapshotEvaluacionQuery.Factor(numero + 1, fila.getLong("id_detalle_evaluacion"),
                        fila.getLong("id_regla"), fila.getString("variable"), fila.getString("operador_aplicado"),
                        fila.getString("valor_condicion_aplicado"), fila.getInt("puntaje_regla_aplicado"),
                        fila.getBoolean("condicion_cumplida"), fila.getInt("puntaje_obtenido")));
        Cabecera cabecera = cabeceras.get(0);
        return Optional.of(new SnapshotEvaluacionQuery(cabecera.idEvaluacion(), cabecera.idSolicitante(),
                cabecera.fechaEvaluacion(), cabecera.scoreTotal(), cabecera.categoria(), cabecera.versionPolitica(),
                cabecera.estado(), factores));
    }

    private record Cabecera(long idEvaluacion, long idSolicitante, java.time.LocalDateTime fechaEvaluacion,
            int scoreTotal, String categoria, int versionPolitica, String estado) {
    }
}
