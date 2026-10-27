package com.crediticio.evaluations.ports.output;

import java.util.Optional;

public interface SnapshotEvaluacionQueryPort {

    Optional<SnapshotEvaluacionQuery> buscar(long idEvaluacion);
}
