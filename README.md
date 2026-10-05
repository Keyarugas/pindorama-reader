# Pindorama!

Um leitor Android de mangás, manhwas, webtoons e conteúdo compatível, baseado no Mihon.

**Status: Alpha · Android 8.0 ou superior · Apache 2.0**

Pindorama! é um fork independente do [Mihon](https://github.com/mihonapp/mihon).
Não é afiliado nem oficialmente mantido pela equipe Mihon. Mihon continua sendo
o projeto original, ao qual pertencem os créditos do trabalho herdado.
O projeto preserva a licença [Apache 2.0](LICENSE).

O Pindorama ainda está em desenvolvimento. Esta versão Alpha pode conter falhas;
mantenha backups da sua biblioteca antes de testar novas versões.

## Recursos herdados do Mihon

- Leitura de conteúdo local e leitor configurável, com diferentes modos e direções de leitura.
- Biblioteca organizada em categorias e atualização programada de capítulos.
- Temas claros e escuros e criação/restauração de backups.
- Integração com serviços de acompanhamento, como MyAnimeList, AniList, Kitsu e MangaUpdates.
- Compatibilidade com extensões do ecossistema herdado do Mihon.

## O que muda no Pindorama

- Identidade própria, com applicationId base `app.pindorama.reader`.
- Tema Pindorama como padrão quando não existe uma preferência de tema salva.
- Identidade visual própria, com novo ícone do launcher e branding no splash e cabeçalhos.
- Biblioteca Privada com autenticação independente do bloqueio do aplicativo.
- Proteções de tela, privacidade de notificações e sanitização de diagnósticos.
- Firebase Analytics, Crashlytics, `google-services.json` e a infraestrutura Gradle
  relacionada foram removidos, assim como código, preferências e textos associados.
  O classpath de runtime atual não inclui Firebase ou Google Play Services.
- Criação e restauração de backups protegidos por senha disponíveis nas configurações.
- Atualizador automático do Mihon desabilitado; não há atualizador próprio nesta etapa.
- Ajuste da dependência FlexibleAdapter para um artefato disponível no Maven Central.

## Fontes, extensões e privacidade

Extensões e fontes são componentes externos e não fazem parte do Pindorama.
O aplicativo não fornece nem hospeda conteúdo. Disponibilidade e funcionamento de
cada extensão dependem dos seus mantenedores e do serviço acessado; a compatibilidade
com o ecossistema não garante o funcionamento de todas as extensões.

A remoção de Firebase Analytics/Crashlytics não impede conexões necessárias às
fontes e aos serviços de acompanhamento que você utiliza. Extensões, fontes e
serviços externos podem tratar dados fora das proteções do Pindorama e possuem
suas próprias práticas de privacidade. O aplicativo não promete anonimato nem
ausência de comunicação de rede ou telemetria de componentes externos.

A Biblioteca Privada controla a apresentação e o acesso ao conteúdo no aplicativo;
não criptografa o banco local, capas, caches ou downloads. Essas proteções não
substituem a segurança do dispositivo e não protegem contra root, sistema
comprometido ou código de extensões executado no processo.

## Backup e restauração

Backups protegidos por senha podem ser criados e restaurados pela interface.
A restauração protegida foi testada em dispositivo Android real. A senha não é
persistida; guarde-a com cuidado, pois perdê-la impede a restauração do arquivo.
A interface distingue senha incorreta, arquivo inválido, formato incompatível,
operação ocupada e cancelamento, com nova tentativa em estados recuperáveis.

A criação usa uma cópia lógica consistente dos dados. A restauração coordena as
operações concorrentes relevantes para evitar gravações conflitantes ou resultados
antigos. Isso não representa uma garantia contra toda falha de armazenamento ou
interrupção do processo. A proteção do arquivo de backup não criptografa os dados
locais do aplicativo. Backups convencionais continuam disponíveis e podem conter
dados sensíveis sem criptografia. Mantenha cópias seguras antes de atualizar.

## Distribuição e desenvolvimento

O repositório do projeto é [Keyarugas/pindorama-reader](https://github.com/Keyarugas/pindorama-reader).
A última release pública é a [Pindorama! 0.2 Alpha 1](https://github.com/Keyarugas/pindorama-reader/releases/tag/v0.2.0-alpha.1).
O código atual está à frente dessa release e prepara a Pindorama! 0.5 Alpha 1;
os ciclos 0.3 e 0.4 foram desenvolvimento interno, sem releases públicas.
Os APKs debug gerados pela CI são artefatos de teste, não releases de distribuição.
O signing próprio de release já está configurado; consulte
[docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md). A CI atual gera APKs debug e
não publica releases automaticamente.

Use as [Issues](https://github.com/Keyarugas/pindorama-reader/issues) para relatos
e as [Pull Requests](https://github.com/Keyarugas/pindorama-reader/pulls) para contribuições.

Para compilar, use JDK 21 e o Android SDK configurado no ambiente:

```sh
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

Consulte [CONTRIBUTING.md](CONTRIBUTING.md) e o
[Código de Conduta](CODE_OF_CONDUCT.md). Bugs e contribuições do Pindorama devem
ser encaminhados às Issues e Pull Requests deste repositório.

## Relação com o projeto original

O remoto `upstream` identifica [mihonapp/mihon](https://github.com/mihonapp/mihon).
As alterações do fork são mantidas em commits próprios; atualizações do upstream
precisam ser revisadas e validadas antes de serem incorporadas.

Os links abaixo pertencem ao **Mihon original**, não ao suporte do Pindorama:

- [Site e documentação](https://mihon.app/)
- [Site: código-fonte](https://github.com/mihonapp/website/)
- [Biblioteca bitmap.kt](https://github.com/mihonapp/bitmap.kt/)
- [Contribuição e traduções upstream](https://mihon.app/docs/contribute)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
