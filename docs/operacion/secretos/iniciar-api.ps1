<#
.SYNOPSIS
    Arranca scoring-api o evaluation-query-api con su rol de ejecución y, opcionalmente, con la saga de HU14.

.DESCRIPTION
    Lee la configuración no secreta y la contraseña cifrada con DPAPI que dejó configurar-secretos.ps1, y las
    pasa como variables de entorno solo al proceso de la API (no a tu sesión ni al registro). No imprime ningún
    secreto. Flyway solo valida: este lanzador nunca ejecuta migraciones.

.EXAMPLE
    .\iniciar-api.ps1 -Api scoring -Jar ..\..\..\target\motor-scoring-crediticio-0.0.1-SNAPSHOT.jar -Saga
    .\iniciar-api.ps1 -Api consulta -Jar ..\..\..\..\evaluation-query-api\target\evaluation-query-api-0.0.1-SNAPSHOT.jar -Saga
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('scoring', 'consulta')] [string] $Api,
    [Parameter(Mandatory)] [string] $Jar,
    [string] $Carpeta = (Join-Path $HOME '.motor-scoring'),
    [switch] $Saga,
    # Solo comprueba la configuración y la conexión a la base con el rol de ejecución; no arranca la API
    [switch] $SoloComprobar,
    [string] $Psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
)
$ErrorActionPreference = 'Stop'

$config = Get-Content -Raw (Join-Path $Carpeta 'configuracion.json') | ConvertFrom-Json
$rol = if ($Api -eq 'scoring') { $config.rolScoring } else { $config.rolConsulta }
$seguro = Get-Content (Join-Path $Carpeta "$rol.dpapi") | ConvertTo-SecureString
$puntero = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro)
$url = "postgresql://$($config.servidor):$($config.puerto)/$($config.base)"
$archivo = { param($ruta) 'file:' + ($ruta -replace '\\', '/') }

$inicio = New-Object Diagnostics.ProcessStartInfo 'java'
$inicio.UseShellExecute = $false
$entorno = $inicio.EnvironmentVariables
foreach ($nombre in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'SPRING_FLYWAY_USER', 'SPRING_FLYWAY_PASSWORD')) {
    $entorno.Remove($nombre)  # nunca se hereda la configuración de administrador de la sesión
}
try {
    $clave = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntero)
    $entorno['AUTH_JWT_ISSUER'] = $config.emisorAuth
    if ($Saga) {
        # El emisor S1 es distinto del emisor de Auth y debe coincidir en Scoring y Query.
        $entorno['SCORING_SERVICE_JWT_ISSUER'] = 'scoring-api'
    }
    if ($Api -eq 'scoring') {
        $entorno['DB_URL'] = $url; $entorno['DB_USERNAME'] = $rol; $entorno['DB_PASSWORD'] = $clave
        $entorno['SCORING_JWT_PUBLIC_KEY_LOCATION'] = & $archivo $config.clavePublicaAuth
        $entorno['SCORING_SAGA_HABILITADA'] = "$([bool]$Saga)".ToLower()
        if ($Saga) {
            $entorno['EVALUATION_QUERY_API_BASE_URL'] = $config.saga.urlEvaluationQuery
            $entorno['SCORING_SERVICE_JWT_KEY_ID'] = $config.saga.idClave
            $entorno['SCORING_SERVICE_JWT_PRIVATE_KEY_LOCATION'] = & $archivo $config.saga.clavePrivada
            $entorno['SCORING_SERVICE_JWT_PUBLIC_KEY_LOCATION'] = & $archivo $config.saga.clavePublica
        }
    } else {
        $entorno['EVALUATION_QUERY_DB_URL'] = $url; $entorno['EVALUATION_QUERY_DB_USERNAME'] = $rol
        $entorno['EVALUATION_QUERY_DB_PASSWORD'] = $clave
        $entorno['EVALUATION_QUERY_JWT_PUBLIC_KEY_LOCATION'] = & $archivo $config.clavePublicaAuth
        $entorno['EVALUATION_QUERY_SAGA_HABILITADA'] = "$([bool]$Saga)".ToLower()
        if ($Saga) {
            $entorno['SCORING_SERVICE_JWT_KEY_ID'] = $config.saga.idClave
            $entorno['SCORING_SERVICE_JWT_PUBLIC_KEY_LOCATION'] = & $archivo $config.saga.clavePublica
        }
    }
    if ($SoloComprobar) {
        $comprobacion = New-Object Diagnostics.ProcessStartInfo $Psql
        $comprobacion.UseShellExecute = $false; $comprobacion.RedirectStandardOutput = $true
        $comprobacion.Arguments = "-h $($config.servidor) -p $($config.puerto) -U $rol -d $($config.base) -At -c `"SELECT current_user`""
        $comprobacion.EnvironmentVariables['PGPASSWORD'] = $clave
        $proceso = [Diagnostics.Process]::Start($comprobacion)
        $usuario = $proceso.StandardOutput.ReadToEnd().Trim(); $proceso.WaitForExit()
        if ($proceso.ExitCode -ne 0 -or $usuario -ne $rol) { throw "No se pudo conectar como $rol" }
        $claves = @($entorno.Keys | Where-Object { $_ -match '^(DB_|EVALUATION_QUERY_|SCORING_|AUTH_JWT)' } | Sort-Object)
        Write-Host "Conexión correcta como $rol. Variables preparadas (sin valores): $($claves -join ', ')"
        return
    }
    $inicio.Arguments = "-jar `"$((Resolve-Path $Jar).Path)`""
    $proceso = [Diagnostics.Process]::Start($inicio)
    Write-Host "$Api iniciada (PID $($proceso.Id)) como $rol; saga: $([bool]$Saga)."
    $proceso.WaitForExit()
} finally {
    $clave = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntero)
}
