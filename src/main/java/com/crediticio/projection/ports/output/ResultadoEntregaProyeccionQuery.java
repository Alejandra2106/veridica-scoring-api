package com.crediticio.projection.ports.output;

public sealed interface ResultadoEntregaProyeccionQuery {
    record Exito() implements ResultadoEntregaProyeccionQuery {}
    record ErrorTransitorio(String codigo, String detalle) implements ResultadoEntregaProyeccionQuery {}
    record ErrorPermanente(String codigo, String detalle) implements ResultadoEntregaProyeccionQuery {}
}
