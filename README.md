# Telegram para Android

Cliente Android nativo em Kotlin/Jetpack Compose, usando TDLib para falar com a rede Telegram. Este projeto é uma implementação independente para Android; não é uma conversão automática do aplicativo iOS nem um cliente oficial do Telegram.

## Funcionalidades já conectadas

- Autenticação de usuário por número de telefone, código, senha de duas etapas e etapas de e-mail/cadastro solicitadas pela conta.
- Lista de conversas e histórico recente por chat, com atualizações em tempo real do TDLib.
- Envio de mensagens de texto, fotos da galeria (até 10 MB) e arquivos (até 2 GB), com legenda opcional.
- Pesquisa local de chats e carregamento de mais conversas.
- Banco de dados TDLib mantido no armazenamento privado do app; a chave do banco é protegida pelo Android Keystore.
- Anexos selecionados são copiados para um caminho privado que o TDLib consegue ler; cópias temporárias antigas são removidas após 48 horas.

## Configuração necessária

1. Instale Android Studio, JDK 17 e Android SDK 35.
2. Abra este diretório como projeto Gradle e aguarde a sincronização. A dependência `io.github.tdlib-android:core:0.1.1` inclui TDLib 1.8.x e será baixada pelo Maven Central.
3. Crie suas credenciais de aplicativo em [my.telegram.org/apps](https://my.telegram.org/apps). Na tela inicial do app, informe `api_id` e `api_hash` próprios e depois siga as etapas de autenticação.
4. Use número em formato internacional. O código pode chegar no próprio Telegram, SMS ou outro canal indicado pelo serviço.

As credenciais do aplicativo ficam nas preferências privadas do app; não as adicione ao Git. A sessão local fica dentro do sandbox Android do aplicativo. Desinstalar o app remove a sessão local e a chave do Android Keystore.

## Segurança e escopo

A dependência TDLib acima é um pacote Android de terceiros que contém bibliotecas nativas para Android; não é um binário publicado diretamente pelo Telegram. Antes de usar uma conta importante ou distribuir uma versão, audite a origem/hash do AAR ou substitua-o por uma compilação reproduzível de TDLib oficial. Não use esta build para conversas sensíveis enquanto essa auditoria não for feita.

Este é o primeiro marco funcional: autenticação, chats e mensagens de texto. Ainda faltam baixar/exibir mídia recebida, progresso e retomada de transferências, chamadas de áudio/vídeo, notificações em segundo plano, contatos, grupos/canais e tópicos, stickers/emoji, pesquisa global, múltiplas contas, testes de acessibilidade e validação em dispositivos físicos. Mensagens de chats secretos continuam sujeitas às limitações próprias de clientes TDLib.

## Compilação

O ambiente de trabalho desta sessão não tem JDK, Gradle ou Android SDK, então não foi possível compilar ou testar o APK aqui. A compilação e o login real precisam ser confirmados no Android Studio/emulador ou aparelho Android com acesso à rede.
