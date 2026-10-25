# Telegram para Android

Cliente Android nativo em Kotlin/Jetpack Compose, usando TDLib para falar com a rede Telegram. Este projeto é uma implementação independente para Android; não é uma conversão automática do aplicativo iOS nem um cliente oficial do Telegram.

## Funcionalidades já conectadas

- Autenticação de usuário por número de telefone, código, senha de duas etapas e etapas de e-mail/cadastro solicitadas pela conta.
- Lista de conversas e histórico recente por chat, com paginação para mensagens mais antigas, fixar/desafixar, arquivar/desarquivar conversas, marcar como não lida, respostas, encaminhamento, edição/exclusão de mensagens enviadas e atualizações em tempo real do TDLib.
- Envio de mensagens de texto, fotos da galeria ou câmera (até 10 MB), arquivos (até 2 GB) e stickers dos conjuntos instalados, com legenda opcional para mídia.
- Recebimento de fotos, vídeos, documentos, áudios, mensagens de voz e animações; download sob demanda com progresso, prévia de fotos e abertura de arquivos por outro app.
- Pesquisa local de chats e pesquisa global de mensagens, com abertura da conversa no ponto do resultado.
- Marcação de mensagens como lidas ao abrir uma conversa; respostas, encaminhamento e reações rápidas.
- Notificações locais de novas mensagens quando o processo do app ainda está ativo em segundo plano e a permissão está concedida.
- Banco de dados TDLib mantido no armazenamento privado do app; a chave do banco é protegida pelo Android Keystore.
- Anexos selecionados são copiados para um caminho privado que o TDLib consegue ler; cópias temporárias antigas são removidas após 48 horas.

## Configuração necessária

1. Instale Android Studio, JDK 17 e Android SDK 35.
2. Abra este diretório como projeto Gradle e aguarde a sincronização. A dependência `io.github.tdlib-android:core:0.1.1` inclui TDLib 1.8.x e será baixada pelo Maven Central.
3. Crie suas credenciais de aplicativo em [my.telegram.org/apps](https://my.telegram.org/apps). Na tela inicial do app, informe `api_id` e `api_hash` próprios e depois siga as etapas de autenticação.
4. Use número em formato internacional. O código pode chegar no próprio Telegram, SMS ou outro canal indicado pelo serviço.

As credenciais do aplicativo ficam nas preferências privadas do app; não as adicione ao Git. A sessão local fica dentro do sandbox Android do aplicativo. Desinstalar o app remove a sessão local e a chave do Android Keystore.

## Assinatura persistente para atualizar sem reinstalar

O APK de debug continua sendo gerado para testes rápidos. Para instalar atualizações por cima da mesma instalação, configure uma chave de release persistente. Uma troca da chave de debug atual para a nova chave de release exige **desinstalar uma única vez**; depois, instale sempre o APK `telegram-android-release-*` assinado pela mesma chave. Os `versionCode` das releases do Actions são incrementados com o número da execução.

1. Gere uma chave privada local e guarde uma cópia segura (não a envie por chat e não a coloque no Git):
   ```sh
   keytool -genkeypair -v -keystore telegram-release.jks -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 -alias tg-android
   ```
2. Converta o keystore para Base64. No Linux, use `base64 -w 0 telegram-release.jks`; no macOS, `base64 < telegram-release.jks | tr -d '\n'`. No Windows PowerShell, use `[Convert]::ToBase64String([IO.File]::ReadAllBytes("telegram-release.jks"))`.
3. No GitHub, abra **Settings → Secrets and variables → Actions → New repository secret** e crie estes quatro secrets:
   - `ANDROID_KEYSTORE_BASE64`: conteúdo Base64 do arquivo `.jks`.
   - `ANDROID_KEYSTORE_PASSWORD`: senha do keystore.
   - `ANDROID_KEY_ALIAS`: neste exemplo, `tg-android`.
   - `ANDROID_KEY_PASSWORD`: senha da chave/alias.
4. Faça um push ou execute manualmente o workflow **Android APK**. Baixe `telegram-android-release-*` em **Actions → execução → Artifacts**.

Faça backup do keystore e das senhas. Sem a chave original, futuras versões não poderão atualizar por cima desta instalação. Não use os dados de uma chave de release em pull requests de forks.

## Segurança e escopo

A dependência TDLib acima é um pacote Android de terceiros que contém bibliotecas nativas para Android; não é um binário publicado diretamente pelo Telegram. Antes de usar uma conta importante ou distribuir uma versão, audite a origem/hash do AAR ou substitua-o por uma compilação reproduzível de TDLib oficial. Não use esta build para conversas sensíveis enquanto essa auditoria não for feita.

Este cliente cobre o fluxo básico de autenticação, lista de conversas, mensagens de texto e transferência de anexos. Ainda faltam chamadas de áudio/vídeo, notificações confiáveis em segundo plano, contatos e sincronização de contatos, gerenciamento completo de grupos/canais e tópicos, gestão completa de stickers/conjuntos e painel de emoji, pesquisa global, múltiplas contas, edição/resposta/encaminhamento avançados, retomada de transferências, testes de acessibilidade e validação em dispositivos físicos. Mensagens de chats secretos continuam sujeitas às limitações próprias de clientes TDLib. Portanto, não é equivalente ao conjunto completo de recursos do Telegram oficial.

## Compilação

O ambiente de trabalho desta sessão não tem JDK, Gradle ou Android SDK, então não foi possível compilar ou testar o APK aqui. A compilação e o login real precisam ser confirmados no Android Studio/emulador ou aparelho Android com acesso à rede.
