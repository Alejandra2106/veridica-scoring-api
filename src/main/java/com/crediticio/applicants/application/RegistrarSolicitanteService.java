package com.crediticio.applicants.application;

import com.crediticio.applicants.application.dto.RegistrarSolicitanteRequest;
import com.crediticio.applicants.application.dto.SolicitanteResponse;
import com.crediticio.applicants.domain.DocumentoDuplicadoException;
import com.crediticio.applicants.domain.Solicitante;
import com.crediticio.applicants.ports.input.RegistrarSolicitanteUseCase;
import com.crediticio.applicants.ports.output.SolicitanteRepositoryPort;
import com.crediticio.applicants.ports.output.RegistrarAltaSolicitanteEnProyeccionPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrarSolicitanteService implements RegistrarSolicitanteUseCase {

    private final SolicitanteRepositoryPort solicitanteRepositoryPort;
    private final RegistrarAltaSolicitanteEnProyeccionPort registrarAltaEnProyeccionPort;
    private final SolicitanteMapper solicitanteMapper;

    public RegistrarSolicitanteService(SolicitanteRepositoryPort solicitanteRepositoryPort,
            RegistrarAltaSolicitanteEnProyeccionPort registrarAltaEnProyeccionPort,
            SolicitanteMapper solicitanteMapper) {
        this.solicitanteRepositoryPort = solicitanteRepositoryPort;
        this.registrarAltaEnProyeccionPort = registrarAltaEnProyeccionPort;
        this.solicitanteMapper = solicitanteMapper;
    }

    @Override
    @Transactional
    public SolicitanteResponse registrar(RegistrarSolicitanteRequest request) {
        if (solicitanteRepositoryPort.existePorNumeroDocumento(request.getNumeroDocumento())) {
            throw new DocumentoDuplicadoException();
        }
        Solicitante solicitante = solicitanteMapper.aDominio(request);
        Solicitante solicitanteGuardado = solicitanteRepositoryPort.guardar(solicitante);
        registrarAltaEnProyeccionPort.registrarAlta(solicitanteGuardado.getIdSolicitante());
        return solicitanteMapper.aResponse(solicitanteGuardado);
    }
}
