# Contribuindo com o Pindorama!

Pindorama! é um fork independente do Mihon, sem afiliação oficial com sua equipe.
Contribuições, propostas e bugs específicos do Pindorama devem ser enviados às
Issues e Pull Requests **deste repositório**. Leia também o
[README](README.md) e o [Código de Conduta](CODE_OF_CONDUCT.md).

## Relatos e propostas

Pesquise Issues existentes antes de abrir uma nova. Informe versão/commit do
Pindorama, versão do Android, dispositivo, passos de reprodução, comportamento
esperado e observado. Inclua logs quando úteis, removendo dados pessoais.
Para mudanças maiores, abra uma Issue para discutir o escopo antes da implementação.

Extensões e fontes são externas ao projeto. Problemas específicos desses componentes
devem ser encaminhados aos seus respectivos mantenedores.

## Relação com upstream

[Mihon](https://github.com/mihonapp/mihon) é o projeto original e o remoto `upstream`.
Não encaminhe falhas observadas apenas no Pindorama à equipe Mihon como se fossem
falhas do aplicativo original, nem solicite que ela dê suporte ao fork.
Quando um problema também for reproduzível no Mihon original, um relato upstream
pode ser preparado seguindo suas regras, com reprodução independente e sem
atribuir ao Mihon alterações exclusivas do Pindorama.

Mantemos commits pequenos e compreensíveis. Atualizações de `upstream/main` devem
ser incorporadas em uma branch de trabalho, revisadas e validadas antes de chegar
à branch pública `main` (localmente `pindorama/main`), preservando o histórico compartilhado.

## Desenvolvimento e Pull Requests

Use conhecimentos básicos de Android e Kotlin, JDK 21, Android SDK e Android Studio
ou a linha de comando. Um dispositivo ou emulador é útil para validação visual.
Abra PRs neste repositório, tendo `main` como base, com descrição do
problema, alteração e validação realizada. Relacione a Issue local quando houver.
Para mudanças visuais, inclua imagens e confira temas claros/escuros e tablets.

Execute os checks pertinentes:

```sh
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

Para alterações de banco, execute também `./gradlew verifySqlDelightMigration`.
Mudanças apenas de documentação/workflows precisam de revisão do diff, links locais
e validação dos YAMLs; não exigem recompilar o APK.

## Traduções

Traduções específicas do Pindorama devem ser propostas neste repositório.
O fluxo de [traduções do Mihon](https://mihon.app/docs/contribute#translation)
pertence ao upstream; não há integração própria do Pindorama com esse serviço.

## Licença e autoria

Preserve integralmente a [licença Apache 2.0](LICENSE), os avisos de copyright,
créditos e eventuais arquivos NOTICE. Identifique alterações próprias sem apagar
a autoria herdada. Forks derivados devem distinguir nome, ícone, applicationId,
atualização e serviços de telemetria do aplicativo original.
