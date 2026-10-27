package com.crediticio.applicants.infrastructure.output;

import com.crediticio.applicants.ports.output.RegistrarAltaSolicitanteEnProyeccionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adaptador local PostgreSQL del outbox; participa en la transacción del alta del solicitante. */
@Component
public class EventoProyeccionQueryAdapter implements RegistrarAltaSolicitanteEnProyeccionPort {

    private static final String REGISTRAR_ALTA = """
            INSERT INTO evento_proyeccion_query (tipo_evento, id_agregado, version_payload, payload)
            VALUES ('SOLICITANTE_PROYECTAR', ?, 1, jsonb_build_object('idSolicitante', ?))
            ON CONFLICT (tipo_evento, id_agregado) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventoProyeccionQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void registrarAlta(long idSolicitante) {
        jdbcTemplate.update(REGISTRAR_ALTA, idSolicitante, idSolicitante);
    }
}
