# Castor Android 2.2.4

## Correção de compilação

- Corrigido o tipo de `PlayerActivity.controls` de `View` para `FrameLayout`. A tela já instancia esse objeto como `FrameLayout` e adiciona nele os contêineres da interface e o feedback de busca; o tipo anterior causava três erros `cannot find symbol: method addView(...)` na compilação Java.
- Incrementados `versionCode` para 9 e `versionName` para 2.2.4.

## Validação

- Conferida a correspondência entre o tipo declarado e a instanciação.
- Conferidos os três pontos de `addView` citados no log do GitHub Actions.
- O build Android completo precisa ser confirmado pelo workflow do GitHub Actions; este ambiente não tem Gradle/Android SDK instalados.
