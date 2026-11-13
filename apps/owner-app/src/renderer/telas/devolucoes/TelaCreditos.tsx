import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { dataEHora } from '../sincronizacao/dados';
import {
  CampoDeEscolha,
  ConfirmarAcao,
  novoId,
  reais,
  RodapeDaCotacao,
  useVendedores,
  ValorDoLancamentoNaTela,
} from './Apoio';
import { lerCreditos, type CreditoDoCliente } from './dados';

const T = TEXTOS_DE_DEVOLUCOES;
const C = T.creditos;

/** Créditos dos clientes: a dona vê todos e transfere o saldo inteiro para outro vendedor (ADR-0014, item 9). */
export function TelaCreditos({ ponte }: { ponte: PonteDoCorona }) {
  const [situacao, setSituacao] = useState('ABERTO');
  const [vendedorId, setVendedorId] = useState('');
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () => chamar(ponte, 'creditos.listar', { situacao, vendedorId }),
    `creditos-${situacao}-${vendedorId}`,
  );
  const [transferindo, setTransferindo] = useState<{
    credito: CreditoDoCliente;
    novoId: string;
  } | null>(null);
  const [paraQuem, setParaQuem] = useState('');
  const [feito, setFeito] = useState<string | null>(null);
  const creditos = busca.dados ? lerCreditos(busca.dados) : [];
  // Quem já usa o crédito não aparece como destino: passar para o mesmo vendedor não faz sentido.
  const destinos = vendedores.opcoes.filter((o) => o.valor !== transferindo?.credito.vendedorId);

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{C.titulo}</h1>
      <p className="gestao-texto">{C.ajuda}</p>
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="creditos-situacao"
          rotulo={T.situacao}
          valor={situacao}
          aoMudar={(v) => {
            setSituacao(v);
            setFeito(null);
          }}
          opcoes={C.situacoes}
        />
        <CampoDeEscolha
          id="creditos-vendedor"
          rotulo={T.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={T.todosOsVendedores}
        />
      </div>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={C.legenda}
        textoVazio={C.vazio}
        colunas={[
          { chave: 'quando', titulo: C.colunas.geradoEm },
          { chave: 'cliente', titulo: C.colunas.cliente },
          { chave: 'vendedor', titulo: C.colunas.vendedor },
          { chave: 'origem', titulo: C.colunas.origem },
          { chave: 'valor', titulo: C.colunas.valor, numerica: true },
          { chave: 'usado', titulo: C.colunas.usado, numerica: true },
          { chave: 'saldo', titulo: C.colunas.saldo, numerica: true },
          { chave: 'situacao', titulo: C.colunas.situacao },
          { chave: 'acoes', titulo: C.colunas.acoes },
        ]}
        linhas={creditos.map((c) => ({
          quando: dataEHora(c.geradoEm),
          cliente: c.clienteNome,
          vendedor: vendedores.nomeDe(c.vendedorId),
          origem: C.origens[c.origem] ?? '',
          valor: reais(c.valor),
          usado: reais(c.valorUtilizado),
          saldo: <ValorDoLancamentoNaTela valor={c.saldo} />,
          situacao: C.situacaoDoCredito[c.situacao] ?? '',
          acoes:
            c.situacao === 'ABERTO' ? (
              <Botao
                aria-label={C.transferirDe(c.clienteNome)}
                onClick={() => {
                  setParaQuem('');
                  setTransferindo({ credito: c, novoId: novoId() });
                }}
              >
                {C.transferir}
              </Botao>
            ) : (
              ''
            ),
        }))}
      />
      <RodapeDaCotacao />
      {transferindo ? (
        <ConfirmarAcao
          titulo={C.confirmarTransferir.titulo}
          rotuloConfirmar={C.transferir}
          executar={() =>
            paraQuem === ''
              ? Promise.resolve({
                  ok: false as const,
                  erro: {
                    tipo: 'campos' as const,
                    mensagem: C.confirmarTransferir.aviso,
                    campos: {},
                  },
                })
              : chamar(ponte, 'creditos.transferir', {
                  id: transferindo.credito.id,
                  novoId: transferindo.novoId,
                  vendedorId: paraQuem,
                })
          }
          aoFeito={() => {
            setTransferindo(null);
            setFeito(C.transferido);
            busca.recarregar();
          }}
          aoCancelar={() => setTransferindo(null)}
        >
          <p>{C.confirmarTransferir.texto(reais(transferindo.credito.saldo.real))}</p>
          <CampoDeEscolha
            id="transferir-vendedor"
            rotulo={C.confirmarTransferir.campo}
            valor={paraQuem}
            aoMudar={setParaQuem}
            opcoes={destinos}
            vazio={C.confirmarTransferir.escolha}
          />
        </ConfirmarAcao>
      ) : null}
    </div>
  );
}
