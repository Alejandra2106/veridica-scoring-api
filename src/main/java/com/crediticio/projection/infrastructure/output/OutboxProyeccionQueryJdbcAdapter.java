package com.crediticio.projection.infrastructure.output;

import com.crediticio.projection.domain.EventoProyeccionQuery;
import com.crediticio.projection.ports.output.OutboxProyeccionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adaptador PostgreSQL del outbox; la concesión se confirma antes de hacer cualquier llamada HTTP. */
@Component
public class OutboxProyeccionQueryJdbcAdapter implements OutboxProyeccionQueryPort {
    private static final Logger log = LoggerFactory.getLogger(OutboxProyeccionQueryJdbcAdapter.class);
    private static final String AGOTAR = """
            UPDATE evento_proyeccion_query
               SET estado = 'ERROR_PERMANENTE', id_concesion = NULL, concesion_hasta = NULL,
                   codigo_ultimo_error = 'REINTENTOS_AGOTADOS',
                   ultimo_error = 'Se agotaron los intentos de entrega', actualizado_en = clock_timestamp()
             WHERE estado = 'PENDIENTE' AND intentos >= ? AND proximo_intento <= clock_timestamp()
               AND (concesion_hasta IS NULL OR concesion_hasta < clock_timestamp())
            """;
    private static final String SELECCIONAR = """
            SELECT id_evento, tipo_evento, id_agregado, intentos
              FROM evento_proyeccion_query
             WHERE estado = 'PENDIENTE' AND intentos < ? AND proximo_intento <= clock_timestamp()
               AND (concesion_hasta IS NULL OR concesion_hasta < clock_timestamp())
             ORDER BY proximo_intento, creado_en, id_evento
             LIMIT 1 FOR UPDATE SKIP LOCKED
            """;
    private static final String RECLAMAR = """
            UPDATE evento_proyeccion_query
               SET id_concesion = ?, concesion_hasta = clock_timestamp() + make_interval(secs => ?),
                   intentos = intentos + 1, actualizado_en = clock_timestamp()
             WHERE id_evento = ? AND estado = 'PENDIENTE'
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;

    public OutboxProyeccionQueryJdbcAdapter(JdbcTemplate jdbc, PlatformTransactionManager gestor) {
        this.jdbc = jdbc;
        this.transaccion = new TransactionTemplate(gestor);
        this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<EventoProyeccionQuery> reclamarSiguiente(int maxIntentos, UUID concesion,
            Duration duracionConcesion) {
        return transaccion.execute(status -> {
            int agotados = jdbc.update(AGOTAR, maxIntentos);
            if (agotados > 0) log.error("{} evento(s) de proyección agotaron los intentos; revisar evento_proyeccion_query",
                    agotados);
            List<FilaEvento> filas = jdbc.query(SELECCIONAR,
                    (fila, numero) -> new FilaEvento(fila.getLong("id_evento"),
                            EventoProyeccionQuery.Tipo.valueOf(fila.getString("tipo_evento")),
                            fila.getLong("id_agregado"), fila.getInt("intentos")), maxIntentos);
            if (filas.isEmpty()) return Optional.empty();
            FilaEvento fila = filas.getFirst();
            if (jdbc.update(RECLAMAR, concesion, duracionConcesion.toMillis() / 1000.0, fila.idEvento()) != 1) {
                status.setRollbackOnly();
                return Optional.empty();
            }
            return Optional.of(new EventoProyeccionQuery(fila.idEvento(), fila.tipo(), fila.idAgregado(),
                    fila.intentos() + 1, concesion));
        });
    }

    @Override
    public boolean registrarEntregado(long idEvento, UUID concesion) {
        return jdbc.update("""
                UPDATE evento_proyeccion_query
                   SET estado = 'ENTREGADO', entregado_en = clock_timestamp(), id_concesion = NULL,
                       concesion_hasta = NULL, codigo_ultimo_error = NULL, ultimo_error = NULL,
                       actualizado_en = clock_timestamp()
                 WHERE id_evento = ? AND id_concesion = ? AND estado = 'PENDIENTE'
                """, idEvento, concesion) == 1;
    }

    @Override
    public boolean registrarErrorTransitorio(long idEvento, UUID concesion, Duration espera, String codigo,
            String detalle) {
        return jdbc.update("""
                UPDATE evento_proyeccion_query
                   SET proximo_intento = clock_timestamp() + make_interval(secs => ?),
                       id_concesion = NULL, concesion_hasta = NULL, codigo_ultimo_error = ?, ultimo_error = ?,
                       actualizado_en = clock_timestamp()
                 WHERE id_evento = ? AND id_concesion = ? AND estado = 'PENDIENTE'
                """, espera.toMillis() / 1000.0, truncar(codigo, 50), truncar(detalle, 500), idEvento,
                concesion) == 1;
    }

    @Override
    public boolean registrarErrorPermanente(long idEvento, UUID concesion, String codigo, String detalle) {
        return jdbc.update("""
                UPDATE evento_proyeccion_query
                   SET estado = 'ERROR_PERMANENTE', id_concesion = NULL, concesion_hasta = NULL,
                       codigo_ultimo_error = ?, ultimo_error = ?, actualizado_en = clock_timestamp()
                 WHERE id_evento = ? AND id_concesion = ? AND estado = 'PENDIENTE'
                """, truncar(codigo, 50), truncar(detalle, 500), idEvento, concesion) == 1;
    }

    private static String truncar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    private record FilaEvento(long idEvento, EventoProyeccionQuery.Tipo tipo, long idAgregado, int intentos) {}
}
