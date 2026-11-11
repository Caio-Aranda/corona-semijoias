import { Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { dataNaTela } from '../cadastros/definicoes';
import { Dinheiro } from './Dinheiro';
import { lerMaleta, lerPaginaDeMaletas, type Maleta } from './dados';

const T = TEXTOS_DE_MALETAS.rascunhos;

export interface ListaDeRascunhosProps {
  ponte: PonteDoCorona;
  aoRetomar: (maleta: Maleta) => void;
  /** Muda quando a lista precisa ser buscada de novo (ex.: voltou da montagem). */
  chave: string;
}

/** Maletas em montagem: continuar de onde parou ou excluir (o estoque nunca foi mexido). */
export function ListaDeRascunhos({ ponte, aoRetomar, chave }: ListaDeRascunhosProps) {
  const busca = useBusca(
    () => chamar(ponte, 'maletas.listar', { status: 'RASCUNHO', pagina: 0, tamanho: 100 }),
    `rascunhos-${chave}`,
  );
  const [excluindo, setExcluindo] = useState<Maleta | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const rascunhos = busca.dados ? lerPaginaDeMaletas(busca.dados) : [];

  async function retomar(r: Maleta) {
    setErro(null);
    // Busca de novo: a lista pode estar desatualizada em relação às peças e à versão.
    const v = await chamar(ponte, 'maletas.ver', { id: r.id });
    if (!v.ok) {
      setErro(v.erro);
      return;
    }
    const maleta = lerMaleta(v.dados);
    if (maleta) aoRetomar(maleta);
  }

  async function excluir() {
    if (!excluindo || ocupado) return;
    setOcupado(true);
    // A lista traz a versão do momento; se mudou, o servidor avisa e a pessoa atualiza.
    const r = await chamar(ponte, 'maletas.excluir', {
      id: excluindo.id,
      version: excluindo.version,
    });
    setOcupado(false);
    setExcluindo(null);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setErro(null);
    busca.recarregar();
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-rascunhos">
      <h2 id="titulo-rascunhos" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazia}
        colunas={[
          { chave: 'vendedor', titulo: T.colunas.vendedor },
          { chave: 'semana', titulo: T.colunas.semana },
          { chave: 'praca', titulo: T.colunas.praca },
          { chave: 'pecas', titulo: T.colunas.pecas, numerica: true },
          { chave: 'valor', titulo: T.colunas.valor, numerica: true },
          { chave: 'criada', titulo: T.colunas.criada },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={rascunhos.map((r) => ({
          vendedor: r.vendedorNome,
          semana: String(r.semanaNumero),
          praca: r.pracaNome,
          pecas: r.totalPecas,
          valor: <Dinheiro valor={r.totalValor} />,
          criada: dataNaTela(r.criadaEm.slice(0, 10)),
          acoes: (
            <div className="gestao-acoes-da-linha">
              <Botao aria-label={T.retomarDe(r.vendedorNome)} onClick={() => void retomar(r)}>
                {T.retomar}
              </Botao>
              <Botao aria-label={T.excluirDe(r.vendedorNome)} onClick={() => setExcluindo(r)}>
                {T.excluir}
              </Botao>
            </div>
          ),
        }))}
      />
      {excluindo ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarExcluir.titulo}
          rotuloConfirmar={ocupado ? T.confirmarExcluir.excluindo : T.confirmarExcluir.excluir}
          rotuloCancelar={T.confirmarExcluir.voltar}
          perigosa
          ocupado={ocupado}
          aoCancelar={() => setExcluindo(null)}
          aoConfirmar={() => void excluir()}
        >
          {T.confirmarExcluir.texto(
            excluindo.vendedorNome,
            TEXTOS_DE_MALETAS.pecas(excluindo.totalPecas),
          )}
        </JanelaDeConfirmacao>
      ) : null}
    </section>
  );
}
