# Assinatura de distribuição do Pindorama!

A assinatura release é independente da assinatura debug. Não existe fallback
para a chave debug. `release`, `foss`, `nightly` e `benchmark` usam a configuração
`pindoramaRelease` e exigem credenciais locais antes de compilar/empacotar.
Não altere a chave entre versões: guarde backups privados do keystore e das
credenciais para continuar distribuindo atualizações assinadas pela mesma chave.

## Criar a chave manualmente

Execute em seu terminal, com `keytool` do JDK instalado:

```sh
mkdir -p "$HOME/.local/share/pindorama/signing"
chmod 700 "$HOME/.local/share/pindorama/signing"
keytool -genkeypair -v -storetype JKS -keyalg RSA -keysize 4096 \
  -validity 10000 -alias pindorama-release \
  -keystore "$HOME/.local/share/pindorama/signing/pindorama-release.jks"
chmod 600 "$HOME/.local/share/pindorama/signing/pindorama-release.jks"
```

Escolha as senhas e preencha os dados do certificado nos prompts interativos.
Não use `-storepass`/`-keypass` na linha de comando, não envie senhas por chat
e não execute novamente a criação sobre uma chave existente.

## Configuração local

Opção recomendada: variáveis de ambiente, lidas pelo Gradle. Exemplo em Bash,
executado por você na mesma sessão em que iniciará o Gradle:

```bash
export PINDORAMA_KEYSTORE_PATH="$HOME/.local/share/pindorama/signing/pindorama-release.jks"
export PINDORAMA_KEY_ALIAS=pindorama-release
read -r -s -p 'Senha do keystore: ' PINDORAMA_KEYSTORE_PASSWORD
printf '\n'
read -r -s -p 'Senha da chave: ' PINDORAMA_KEY_PASSWORD
printf '\n'
export PINDORAMA_KEYSTORE_PASSWORD PINDORAMA_KEY_PASSWORD
```

Se escolheu a mesma senha para a chave e o keystore, informe-a nos dois prompts.
Não use shell tracing (`set -x`) nem imprima o ambiente.

Alternativamente, crie `keystore.properties` na raiz do repositório, ignorado pelo
Git, com permissão `600`. Preencha localmente estas propriedades; não versione
nem compartilhe esse arquivo:

```properties
storeFile=/CAMINHO/ABSOLUTO/FORA/DO/REPOSITORIO/pindorama-release.jks
storePassword=PREENCHER_LOCALMENTE
keyAlias=pindorama-release
keyPassword=PREENCHER_LOCALMENTE
```

Use o caminho absoluto real; `~` e `$HOME` não são expandidos no arquivo properties.
As variáveis de ambiente têm prioridade sobre as propriedades correspondentes.
O formato Java Properties exige escape de barras invertidas e outros caracteres
especiais; prefira o ambiente para senhas que contenham esses caracteres.
O antigo fluxo `MIHON_GITHUB_RELEASE` e suas variáveis não são utilizados.

## Validar e gerar, somente após configurar credenciais

```sh
./gradlew :app:validatePindoramaReleaseSigning
./gradlew :app:assembleRelease
```

A primeira tarefa verifica presença dos campos e acesso ao arquivo sem imprimir
valores. A validade das senhas e da chave é verificada na assinatura pelo Android
Gradle Plugin. Debug continua disponível sem credenciais release.

Antes de publicar, conferir o APK com `apksigner verify --verbose --print-certs`
e `apkanalyzer manifest application-id`, `version-name` e `version-code`.
Comparar o certificado com a chave local, confirmar `app.pindorama.reader`,
versionName `0.1.0` e versionCode `29`, além de verificar a ausência das dependências
Firebase Analytics/Crashlytics no runtime release e os campos gerados
`TELEMETRY_INCLUDED=false` e `UPDATER_ENABLED=false`.
Nenhuma dessas verificações substitui a geração de um APK release assinado.

Esta configuração não publica APKs, não cria GitHub Releases e não configura secrets
no GitHub. Não use `signingReport`, dumps de propriedades ou build scans para
compartilhar informações de assinatura sem revisar o conteúdo.
