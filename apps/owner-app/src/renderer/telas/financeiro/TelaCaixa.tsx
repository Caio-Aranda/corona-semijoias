import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { RodapeDaCotacao } from '../../nucleo/cotacao';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { EstornarComMotivo } from './EstornarComMotivo';
import { LancarNoCaixa } from './LancarNoCaixa';
import { ValorDoDia, soReal } from './Valor';
import { lerExtrato, lerSaldo, type LancamentoDoCaixa } from './dados';

const T = TEXTOS_DE_FINANCEIRO.caixa;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

function nomeDoLancamento(l: LancamentoDoCaixa): string {
  return l.descricao || l.vendedorNome || l.fornecedorNome || T.semDescricao;
}

/** Financeiro → Caixa: saldo, extrato do período, lançamento manual e estorno do que foi lançado à mão. */
export function TelaCaixa({ ponte }: { ponte: PonteDoCorona }) {
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [volta, setVolta] = useState(0);
  const [lancando, setLancando] = useState(false);
  const [estornando, setEstornando] = useState<LancamentoDoCaixa | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const saldoBusca = useBusca(() => chamar(ponte, 'caixa.saldo'), `caixa-saldo-${volta}`);
  const extratoBusca = useBusca(
    () => chamar(ponte, 'caixa.extrato', { ...(de ? { de } : {}), ...(ate ? { ate } : {}) }),
    `caixa-extrato-${volta}-${de}-${ate}`,
  );
  const categoriasBusca = useBusca(
    () => chamar(ponte, 'caixa.categorias'),
    `caixa-categorias-${volta}`,
  );
  const saldo = saldoBusca.dados ? lerSaldo(saldoBusca.dados) : null;
  const extrato = extratoBusca.dados ? lerExtrato(extratoBusca.dados) : null;

  const recarregar = () => setVolta((v) => v + 1);

  if (lancando) {
    return (
      <div className="gestao-tela">
        <div className="gestao-tela__topo">
          <h1 className="gestao-titulo">{T.titulo}</h1>
        </div>
        <LancarNoCaixa
          ponte={ponte}
          categoriasBrutas={categoriasBusca.dados}
          aoCancelar={() => setLancando(false)}
          aoFeito={(jaRegistrado) => {
            setLancando(false);
            setAviso(jaRegistrado ? T.novo.jaFeito : T.novo.feito);
            recarregar();
          }}
        />
      </div>
    );
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao
          variante="primario"
          onClick={() => {
            setAviso(null);
            setLancando(true);
          }}
        >
          {T.lancar}
        </Botao>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {saldoBusca.erro ? (
        <AvisoDeErro erro={saldoBusca.erro} aoTentarDeNovo={saldoBusca.recarregar} />
      ) : null}
      {saldo ? (
        <>
          <p className="gestao-texto">
            <strong>{T.saldo}</strong> <ValorDoDia valor={saldo.saldo} />
          </p>
          {saldo.negativo ? (
            <>
              <Aviso tipo="atencao">{T.negativo(soReal(saldo.saldo).replace('-', ''))}</Aviso>
              <p className="gestao-texto">{T.negativoAjuda}</p>
            </>
          ) : null}
        </>
      ) : null}
      <div className="gestao-filtros">
        <Campo
          type="date"
          rotulo={TEXTOS_DE_FINANCEIRO.de}
          value={de}
          onChange={(e) => setDe(e.target.value)}
        />
        <Campo
          type="date"
          rotulo={TEXTOS_DE_FINANCEIRO.ate}
          value={ate}
          onChange={(e) => setAte(e.target.value)}
        />
        {de || ate ? (
          <Botao
            onClick={() => {
              setDe('');
              setAte('');
            }}
          >
            {TEXTOS_DE_FINANCEIRO.limparFiltros}
          </Botao>
        ) : null}
      </div>
      {extratoBusca.erro ? (
        <AvisoDeErro erro={extratoBusca.erro} aoTentarDeNovo={extratoBusca.recarregar} />
      ) : null}
      {extratoBusca.dados && !extrato ? <Aviso tipo="erro">{T.naoLeu}</Aviso> : null}
      {extrato ? (
        <>
          <dl className="gestao-resumo">
            <dt>{T.saldoAnterior}</dt>
            <dd>{soReal(extrato.saldoAnterior)}</dd>
            <dt>{T.entradas}</dt>
            <dd>{soReal(extrato.entradas)}</dd>
            <dt>{T.saidas}</dt>
            <dd>{soReal(extrato.saidas)}</dd>
            <dt>{T.saldoFinal}</dt>
            <dd>{soReal(extrato.saldoFinal)}</dd>
          </dl>
          <Tabela
            legenda={T.legenda}
            textoVazio={T.vazio}
            colunas={[
              { chave: 'data', titulo: T.colunas.data },
              { chave: 'descricao', titulo: T.colunas.descricao },
              { chave: 'categoria', titulo: T.colunas.categoria },
              { chave: 'forma', titulo: T.colunas.forma },
              { chave: 'tipo', titulo: T.colunas.tipo },
              { chave: 'valor', titulo: T.colunas.valor, numerica: true },
              { chave: 'saldoDepois', titulo: T.colunas.saldoDepois, numerica: true },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={extrato.lancamentos.map((l) => ({
              data: dataParaTela(l.dataLancamento),
              descricao: (
                <>
                  {nomeDoLancamento(l)}
                  {l.estornaId ? ` (${T.estorno})` : ''}
                  {l.origemTipo && l.origemTipo !== 'MANUAL' ? (
                    <small className="gestao-texto">
                      {' '}
                      {T.origens[l.origemTipo] ?? T.origemAutomatica}
                    </small>
                  ) : null}
                </>
              ),
              categoria: l.categoria,
              forma: FORMAS[l.forma] ?? l.forma,
              tipo: TEXTOS_DE_FINANCEIRO.tipos[l.tipo] ?? l.tipo,
              valor: soReal(l.valor),
              saldoDepois: l.saldoDepois ? soReal(l.saldoDepois) : '',
              acoes:
                l.origemTipo === 'MANUAL' && !l.estornaId ? (
                  <Botao
                    aria-label={T.estornarDe(nomeDoLancamento(l))}
                    onClick={() => {
                      setAviso(null);
                      setEstornando(l);
                    }}
                  >
                    {T.estornar}
                  </Botao>
                ) : (
                  ''
                ),
            }))}
          />
          <RodapeDaCotacao />
        </>
      ) : null}
      {estornando ? (
        <EstornarComMotivo
          key={estornando.id}
          ponte={ponte}
          operacao="caixa.estornar"
          alvo={{ id: estornando.id }}
          textos={T.estornoDialogo}
          aoCancelar={() => setEstornando(null)}
          aoFeito={() => {
            setEstornando(null);
            setAviso(T.estornoDialogo.feito);
            recarregar();
          }}
        />
      ) : null}
    </div>
  );
}
