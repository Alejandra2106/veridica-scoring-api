package com.crediticio.applicants.ports.output;

/** Registra de forma durable el alta para que la proyección de lectura pueda sincronizarse después. */
public interface RegistrarAltaSolicitanteEnProyeccionPort {

    void registrarAlta(long idSolicitante);
}
