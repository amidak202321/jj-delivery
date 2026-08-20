# JJ Entregador Android

MVP Android nativo em Kotlin.

Fluxo:
1. Primeiro acesso com código de 6 dígitos.
2. Token seguro fica salvo no aparelho.
3. Rota sincroniza a cada 3 segundos enquanto o app está aberto.
4. Nova entrega gera notificação heads-up/local mesmo com o app aberto.
5. GPS atualiza posição e o backend recalcula a sequência.
6. Waze/Google Maps abrem a próxima entrega.
7. Concluir remove a entrega e recalcula a próxima.

Backend:
https://jxpiazntgqasvonvrtpv.supabase.co/functions/v1/jjburger-driver

Próxima etapa:
- FCM para push real com app fechado/segundo plano.
- Serviço foreground para localização contínua durante Waze/Maps.
- gerar APK assinado quando Android SDK/build estiver disponível.
