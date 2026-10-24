# Castor Android 2.2.2

## Correções
- Corrigido o fallback de seek DLNA para TVs que retornam HTTP 500 com `Transition not available`; o código agora reconhece esse erro, pausa e aguarda a TV estabilizar, tenta `REL_TIME` e `ABS_TIME`, e retoma a reprodução quando necessário.
- Incrementada a versão Android para 2.2.2.

## Limites conhecidos
- O player local continua usando o motor de reprodução já integrado ao Castor. O REX Player é baseado em libmpv e sua implementação completa depende de integrar/buildar bibliotecas nativas por ABI e adaptar a camada de UI; não é uma dependência Android simples que possa ser adicionada com segurança sem uma compilação completa.
- Ao transmitir via DLNA/Chromecast, o vídeo é decodificado pelo receptor da TV; os comandos de seek dependem do suporte do receptor/protocolo. Nenhum app de celular consegue forçar seek em um renderer que não o implemente.
