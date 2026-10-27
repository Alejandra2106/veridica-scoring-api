package com.crediticio.projection.infrastructure.output;

import com.crediticio.config.SagaRecomendacionesProperties;
import com.crediticio.config.ServicioJwtProperties;
import com.crediticio.evaluations.infrastructure.output.TokenServicioJwtFirmante;
import com.crediticio.projection.domain.EventoProyeccionQuery;
import com.crediticio.projection.ports.output.PublicarProyeccionQueryPort;
import com.crediticio.projection.ports.output.ResultadoEntregaProyeccionQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
/** Adaptador de salida HTTP; entrega cada tipo de evento mediante su operación interna de Query. */
@Component
@ConditionalOnProperty(prefix = "scoring.saga", name = "habilitada", havingValue = "true")
public class ProyeccionQueryHttpAdapter implements PublicarProyeccionQueryPort {
    private final RestClient cliente;
    private final TokenServicioJwtFirmante firmante;
    private final String permisoProyeccion;

    public ProyeccionQueryHttpAdapter(SagaRecomendacionesProperties propiedades,
            ServicioJwtProperties jwt, TokenServicioJwtFirmante firmante) {
        if (propiedades.urlBase() == null || propiedades.urlBase().isBlank()) {
            throw new IllegalStateException("Falta la propiedad scoring.saga.url-base");
        }
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(propiedades.conexionReenvio()).build());
        fabrica.setReadTimeout(propiedades.limiteReenvio());
        this.cliente = RestClient.builder().baseUrl(propiedades.urlBase()).requestFactory(fabrica).build();
        this.firmante = firmante;
        this.permisoProyeccion = jwt.permisoProyeccion();
    }

    @Override
    public ResultadoEntregaProyeccionQuery publicar(EventoProyeccionQuery evento) {
        String ruta = switch (evento.tipo()) {
            case SOLICITANTE_PROYECTAR -> "/internal/v1/proyeccion/solicitantes/{id}";
            case EVALUACION_COMPLETADA -> "/internal/v1/proyeccion/evaluaciones/{id}/completada";
        };
        try {
            cliente.put().uri(ruta, evento.idAgregado())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + firmante.emitir(permisoProyeccion))
                    .retrieve().toBodilessEntity();
            return new ResultadoEntregaProyeccionQuery.Exito();
        } catch (HttpClientErrorException ex) {
            int codigo = ex.getStatusCode().value();
            if (codigo == 429) return transitorio("LIMITADO", "HTTP 429");
            return permanente("RECHAZO_FUNCIONAL", "HTTP " + codigo);
        } catch (HttpServerErrorException ex) {
            return transitorio("ERROR_REMOTO", "HTTP " + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            return transitorio("SIN_CONEXION", "No se pudo conectar con evaluation-query-api");
        } catch (RestClientException ex) {
            return transitorio("RESPUESTA_INTERRUMPIDA", "La respuesta de evaluation-query-api fue interrumpida");
        } catch (RuntimeException ex) {
            return permanente("CONFIGURACION_LOCAL", "No se pudo preparar la entrega a evaluation-query-api");
        }
    }

    private static ResultadoEntregaProyeccionQuery transitorio(String codigo, String detalle) {
        return new ResultadoEntregaProyeccionQuery.ErrorTransitorio(codigo, detalle);
    }

    private static ResultadoEntregaProyeccionQuery permanente(String codigo, String detalle) {
        return new ResultadoEntregaProyeccionQuery.ErrorPermanente(codigo, detalle);
    }
}
