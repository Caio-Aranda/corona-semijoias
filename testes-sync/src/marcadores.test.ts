import { describe, expect, it } from 'vitest';
import { marcadoresNaResposta } from './marcadores';

const resposta = (corpo: string, status = 200, tipo = 'application/json') => ({
  metodo: 'GET',
  caminho: '/api/vendedor/sync/pull?cursor=x',
  status,
  tipo,
  corpo: Buffer.from(corpo, 'utf8'),
});

describe('varredura das respostas (marcadores proibidos)', () => {
  it('acha custo, fornecedor e as rotas da dona em qualquer grafia', () => {
    expect(marcadoresNaResposta(resposta('{"precoCusto":"1.00"}'))).toHaveLength(2);
    expect(marcadoresNaResposta(resposta('{"FORNECEDOR_ID":"x"}'))).toHaveLength(1);
    expect(
      marcadoresNaResposta(resposta('veja /api/dona/produtos', 200, 'text/plain')),
    ).toHaveLength(1);
    expect(marcadoresNaResposta(resposta('{"nome":"Brinco"}'))).toEqual([]);
  });

  it('não olha dentro de fotos', () => {
    expect(marcadoresNaResposta(resposta('custo', 200, 'image/jpeg'))).toEqual([]);
  });

  it('o achado conhecido vale só para aquele código de erro', () => {
    const texto = 'Peça à dona para liberar este computador no Corona Gestão.';
    expect(
      marcadoresNaResposta(
        resposta(JSON.stringify({ codigo: 'APARELHO_NAO_LIBERADO', mensagem: texto }), 403),
      ),
    ).toEqual([]);
    expect(
      marcadoresNaResposta(resposta(JSON.stringify({ codigo: 'OUTRO', mensagem: texto }), 403)),
    ).toHaveLength(1);
    expect(marcadoresNaResposta(resposta(JSON.stringify({ mensagem: texto })))).toHaveLength(1);
  });
});
