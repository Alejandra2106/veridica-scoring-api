<#
.SYNOPSIS
    Configura los secretos de ejecución de scoring-api y evaluation-query-api sin que salgan de este equipo.

.DESCRIPTION
    1. Pide por teclado (sin mostrarlas) las contraseñas de los roles de ejecución y las fija en PostgreSQL
       como verificador SCRAM-SHA-256 calculado aquí, igual que \password de psql: el servidor nunca recibe la
       contraseña en claro.
    2. Guarda una copia de cada contraseña cifrada con DPAPI (solo tu usuario de Windows en este equipo puede
       descifrarla) para el lanzador iniciar-api.ps1.
    3. Genera el par RSA de la identidad de servicio de la saga (HU14), distinto del de auth-api.
    4. Escribe la configuración no secreta (usuarios, rutas de claves, kid) en configuracion.json.
    La carpeta de secretos queda con permisos solo para tu usuario. Nada se escribe en los repositorios ni se
    imprime ningún secreto.

    Ejecútalo en tu propia terminal de PowerShell (no a través de otra herramienta), conectada a la base como
    administrador. Para PostgreSQL usa PGPASSWORD o, si no está definida, psql la pedirá.

.EXAMPLE
    .\configurar-secretos.ps1 -ClavePublicaAuth 'C:\secretos\jwt-publica.pem'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ClavePublicaAuth,
    [string] $EmisorAuth = 'auth-api',
    [string] $Carpeta = (Join-Path $HOME '.motor-scoring'),
    [string] $Base = 'motor_scoring',
    [string] $Servidor = 'localhost',
    [int] $Puerto = 5432,
    [string] $Administrador = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'postgres' }),
    [string] $RolScoring = 'scoring_app',
    [string] $RolConsulta = 'consulta_app',
    [string] $Psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe',
    [string] $IdClaveSaga = ('saga-' + (Get-Date -Format 'yyyyMM')),
    [switch] $SinClavesSaga,
    # Solo para pruebas automatizadas: contraseñas ya leídas (SecureString), en lugar de pedirlas por teclado
    [hashtable] $ClavesDePrueba
)
$ErrorActionPreference = 'Stop'

function ConvertTo-TextoPlano([Security.SecureString] $Seguro) {
    $puntero = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Seguro)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntero) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntero) }
}

# Verificador SCRAM-SHA-256 en el formato de PostgreSQL (el mismo que calcula \password)
function New-VerificadorScram([Security.SecureString] $Clave) {
    $plano = ConvertTo-TextoPlano $Clave
    try {
        if ($plano -notmatch '^[\x21-\x7E]{16,}$') {
            throw 'La contraseña debe tener al menos 16 caracteres ASCII imprimibles, sin espacios.'
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes($plano)
    } finally { $plano = $null }
    $sal = New-Object byte[] 16
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($sal)
    $pbkdf2 = New-Object Security.Cryptography.Rfc2898DeriveBytes -ArgumentList $bytes, $sal, 4096,
        ([Security.Cryptography.HashAlgorithmName]::SHA256)
    $saltedPassword = $pbkdf2.GetBytes(32)
    [Array]::Clear($bytes, 0, $bytes.Length)
    $hmac = [Security.Cryptography.HMACSHA256]::new($saltedPassword)
    $claveCliente = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Client Key'))
    $claveServidor = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Server Key'))
    $claveAlmacenada = [Security.Cryptography.SHA256]::Create().ComputeHash($claveCliente)
    'SCRAM-SHA-256$4096:{0}${1}:{2}' -f [Convert]::ToBase64String($sal),
        [Convert]::ToBase64String($claveAlmacenada), [Convert]::ToBase64String($claveServidor)
}

function Read-ClaveConfirmada([string] $Rol) {
    if ($ClavesDePrueba) { return $ClavesDePrueba[$Rol] }
    while ($true) {
        $primera = Read-Host -AsSecureString "Contraseña nueva para $Rol (mínimo 16 caracteres)"
        $segunda = Read-Host -AsSecureString "Repite la contraseña de $Rol"
        $a = ConvertTo-TextoPlano $primera; $b = ConvertTo-TextoPlano $segunda
        $iguales = $a -ceq $b; $a = $null; $b = $null
        if ($iguales) { return $primera }
        Write-Warning 'Las contraseñas no coinciden; vuelve a intentarlo.'
    }
}

function Invoke-Psql([string] $Sentencia) {
    & $Psql -h $Servidor -p $Puerto -U $Administrador -d $Base -At -v ON_ERROR_STOP=1 -c $Sentencia
    if ($LASTEXITCODE -ne 0) { throw "psql terminó con código $LASTEXITCODE" }
}

function Set-PermisosSoloUsuario([string] $Ruta) {
    & icacls $Ruta /inheritance:r /grant:r "${env:USERDOMAIN}\${env:USERNAME}:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "No se pudieron restringir los permisos de $Ruta" }
}

if (-not (Test-Path $ClavePublicaAuth)) { throw "No existe la clave pública de auth-api: $ClavePublicaAuth" }
if (-not (Test-Path $Psql)) { throw "No se encontró psql en $Psql" }
if ($Base -ne (Invoke-Psql 'SELECT current_database()')) { throw 'La conexión no apunta a la base indicada' }

New-Item -ItemType Directory -Force -Path $Carpeta | Out-Null
Set-PermisosSoloUsuario $Carpeta

# 1 y 2. Contraseñas de los roles de ejecución
foreach ($rol in @($RolScoring, $RolConsulta)) {
    if ($rol -notmatch '^[a-z_][a-z0-9_]*$') { throw "Nombre de rol no válido: $rol" }
    if ((Invoke-Psql "SELECT count(*) FROM pg_roles WHERE rolname = '$rol' AND NOT rolsuper") -ne '1') {
        throw "El rol $rol no existe o es superusuario"
    }
    $clave = Read-ClaveConfirmada $rol
    $verificador = New-VerificadorScram $clave
    Invoke-Psql "ALTER ROLE $rol PASSWORD '$verificador'" | Out-Null
    $clave | ConvertFrom-SecureString | Set-Content -Encoding ASCII (Join-Path $Carpeta "$rol.dpapi")
    Write-Host "Contraseña de $rol fijada (verificador SCRAM) y guardada cifrada con DPAPI."
}

# 3. Par RSA de la saga
$carpetaSaga = Join-Path $Carpeta 'saga'
if (-not $SinClavesSaga) {
    $openssl = (Get-Command openssl -ErrorAction SilentlyContinue).Source
    if (-not $openssl) { $openssl = 'C:\Program Files\Git\mingw64\bin\openssl.exe' }
    New-Item -ItemType Directory -Force -Path $carpetaSaga | Out-Null
    $privada = Join-Path $carpetaSaga "$IdClaveSaga-privada.pem"
    $publica = Join-Path $carpetaSaga "$IdClaveSaga-publica.pem"
    if (Test-Path $privada) { throw "Ya existe una clave con el kid $IdClaveSaga; usa otro -IdClaveSaga para rotar" }
    $ErrorActionPreference = 'Continue'  # openssl escribe su progreso en stderr
    & $openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $privada 2>&1 | Out-Null
    & $openssl pkey -in $privada -pubout -out $publica 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path $publica)) { throw 'No se pudo generar el par de claves de la saga' }
    Set-PermisosSoloUsuario $carpetaSaga
    Write-Host "Par RSA de la saga generado con el kid $IdClaveSaga."
}

# 4. Configuración no secreta para iniciar-api.ps1
$configuracion = [ordered]@{
    servidor = $Servidor; puerto = $Puerto; base = $Base
    emisorAuth = $EmisorAuth; clavePublicaAuth = (Resolve-Path $ClavePublicaAuth).Path
    rolScoring = $RolScoring; rolConsulta = $RolConsulta
    saga = [ordered]@{
        idClave = $IdClaveSaga
        clavePrivada = (Join-Path $carpetaSaga "$IdClaveSaga-privada.pem")
        clavePublica = (Join-Path $carpetaSaga "$IdClaveSaga-publica.pem")
        urlEvaluationQuery = 'http://localhost:8082'
    }
}
$configuracion | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $Carpeta 'configuracion.json')
Write-Host "Listo. Carpeta: $Carpeta (permisos solo para ${env:USERNAME}). Arranca las API con iniciar-api.ps1."
