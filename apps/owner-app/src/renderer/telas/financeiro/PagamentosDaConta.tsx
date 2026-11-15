import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { EstornarComMotivo } from './EstornarComMotivo';
import { soReal } from './Valor';
import { baixaPodeSerEstornada, lerConta, type BaixaDaConta } from './contas-a-pagar';

const T = TEXTOS_DE_FINANCEIRO.contasAPagar;
const B = T.baixas;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

/** Os pagamentos de uma parcela, com o estorno de cada pagamento que ainda vale. */
export function PagamentosDaConta({
  ponte,
  contaId,
  aoVoltar,
}: {
  ponte: PonteDoCorona;
  contaId: string;
  aoVoltar: () => void;
}) {
  const [volta, setVolta] = useState(0);
  const [estornando, setEstornando] = useState<BaixaDaConta | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const busca = useBusca(
    () => chamar(ponte, 'contasAPagar.ver', { id: contaId }),
    `conta-a-pagar-${contaId}-${volta}`,
  );
  const conta = busca.dados ? lerConta(busca.dados) : null;

  return (
    <div>
      <div className="gestao-acoes-da-linha">
        <Botao onClick={aoVoltar}>{T.voltar}</Botao>
      </div>
      {busca.erro && !conta ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}
      {busca.dados && !conta ? <Aviso tipo="erro">{TEXTOS_DE_FINANCEIRO.naoLeu}</Aviso> : null}
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {conta ? (
        <>
          <h2 className="gestao-subtitulo">{B.titulo}</h2>
          <p className="gestao-texto">
            {conta.fornecedorNome}, {T.parcela(conta.parcela, conta.totalParcelas)}
          </p>
          <Tabela
            legenda={B.titulo}
            textoVazio={B.vazio}
            colunas={[
              { chave: 'data', titulo: B.colunas.data },
              { chave: 'forma', titulo: B.colunas.forma },
              { chave: 'valor', titulo: B.colunas.valor, numerica: true },
              { chave: 'motivo', titulo: B.colunas.motivo },
              { chave: 'acoes', titulo: B.colunas.acoes },
            ]}
            linhas={conta.baixas.map((b) => ({
              data: b.dataPagamento ? dataParaTela(b.dataPagamento) : '',
              forma:
                b.tipo === 'CANCELAMENTO'
                  ? B.cancelamento
                  : b.estornaId
                    ? `${B.estorno} (${FORMAS[b.forma] ?? b.forma})`
                    : (FORMAS[b.forma] ?? b.forma),
              valor: soReal(b.valor),
              motivo: b.motivo,
              acoes: baixaPodeSerEstornada(b, conta.baixas) ? (
                <Botao
                  aria-label={B.estornarDe(dataParaTela(b.dataPagamento))}
                  onClick={() => {
                    setAviso(null);
                    setEstornando(b);
                  }}
                >
                  {B.estornar}
                </Botao>
              ) : (
                ''
              ),
            }))}
          />
        </>
      ) : null}
      {estornando ? (
        <EstornarComMotivo
          key={estornando.id}
          ponte={ponte}
          operacao="contasAPagar.estornar"
          alvo={{ baixaId: estornando.id }}
          textos={T.estornoDialogo}
          aoCancelar={() => setEstornando(null)}
          aoFeito={() => {
            setEstornando(null);
            setAviso(T.estornoDialogo.feito);
            setVolta((v) => v + 1);
          }}
        />
      ) : null}
    </div>
  );
}
