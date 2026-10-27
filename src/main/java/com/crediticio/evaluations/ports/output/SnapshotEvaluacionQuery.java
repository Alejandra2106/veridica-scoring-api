package com.crediticio.evaluations.ports.output;

import java.time.LocalDateTime;
import java.util.List;

/** Snapshot mínimo persistido que Scoring entrega a la proyección de Query para HU13–HU15. */
public record SnapshotEvaluacionQuery(long idEvaluacion, long idSolicitante, LocalDateTime fechaEvaluacion,
        int scoreTotal, String categoria, int versionPolitica, String estado, List<Factor> factores) {

    public SnapshotEvaluacionQuery {
        factores = List.copyOf(factores);
    }

    public record Factor(int orden, long idDetalleOrigen, long idRegla, String variable, String operadorAplicado,
            String valorCondicionAplicado, int puntajeReglaAplicado, boolean condicionCumplida, int contribucion) {
    }
}
