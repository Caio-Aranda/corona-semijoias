import { Aviso, Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { CAMPOS_DA_TELA_DA_REFERENCIA } from './campos';
import { lerLista, texto, type Registro } from './dados';
import { FormularioDeCampos } from './FormularioDeCampos';

const T = TEXTOS.vendedores;

export interface AbaReferenciasProps {
  ponte: PonteDoCorona;
  vendedorId: string;
  aoMudarSujo: (sujo: boolean) => void;
}

/** Aba "Referências": várias pessoas por vendedor; cada uma se grava e se exclui sozinha. */
export function AbaReferencias({ ponte, vendedorId, aoMudarSujo }: AbaReferenciasProps) {
  const lista = useBusca(
    () => chamar(ponte, 'vendedores.referencias.listar', { id: vendedorId }),
    `ref|${vendedorId}`,
  );
  const [editando, setEditando] = useState<Registro | 'nova' | null>(null);
  const [excluindo, setExcluindo] = useState<Registro | null>(null);
  const [executando, setExecutando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState<string | null>(null);

  async function excluir() {
    if (!excluindo?.id || executando) return;
    setExecutando(true);
    const r = await chamar(ponte, 'vendedores.referencias.excluir', {
      id: vendedorId,
      referenciaId: excluindo.id,
    });
    setExecutando(false);
    setExcluindo(null);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setErro(null);
    setFeito(T.referencias.feito.excluida);
    lista.recarregar();
  }

  if (editando !== null) {
    const registro: Registro = editando === 'nova' ? { version: 0 } : editando;
    return (
      <FormularioDeCampos
        key={registro.id ?? 'nova'}
        idBase="ref"
        campos={CAMPOS_DA_TELA_DA_REFERENCIA}
        registro={registro}
        rotuloSalvar={T.referencias.salvar}
        salvar={(corpo, version) =>
          registro.id
            ? chamar(ponte, 'vendedores.referencias.editar', {
                ...corpo,
                id: vendedorId,
                referenciaId: registro.id,
                version,
              })
            : chamar(ponte, 'vendedores.referencias.criar', { ...corpo, id: vendedorId })
        }
        aoSalvo={() => {
          setEditando(null);
          setErro(null);
          setFeito(T.referencias.feito.salva);
          lista.recarregar();
        }}
        aoAtualizar={() => {
          setEditando(null);
          lista.recarregar();
        }}
        aoMudarSujo={aoMudarSujo}
        rodapeExtra={<Botao onClick={() => setEditando(null)}>{T.referencias.cancelar}</Botao>}
      />
    );
  }

  const itens = lista.dados ? lerLista(lista.dados) : null;
  const linhas = (itens ?? []).map((r) => ({
    nome: texto(r, 'nome'),
    parentesco: texto(r, 'parentesco'),
    telefone: texto(r, 'telefone'),
    acoes: (
      <span className="gestao-acoes-da-linha">
        <Botao
          onClick={() => {
            setFeito(null);
            setEditando(r);
          }}
        >
          {T.referencias.editar}
        </Botao>
        <Botao
          onClick={() => {
            setFeito(null);
            setExcluindo(r);
          }}
        >
          {T.referencias.excluir}
        </Botao>
      </span>
    ),
  }));

  return (
    <div className="gestao-aba">
      <div className="gestao-tela__topo">
        <h2 className="gestao-subtitulo">{T.referencias.titulo}</h2>
        <Botao
          variante="primario"
          onClick={() => {
            setFeito(null);
            setEditando('nova');
          }}
        >
          {T.referencias.nova}
        </Botao>
      </div>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={lista.recarregar} /> : null}
      {lista.erro ? <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} /> : null}
      {itens === null && !lista.erro ? <p className="gestao-texto">{T.ficha.carregando}</p> : null}
      {itens !== null ? (
        <Tabela
          legenda={T.referencias.legenda}
          colunas={[
            { chave: 'nome', titulo: T.referencias.colunas.nome },
            { chave: 'parentesco', titulo: T.referencias.colunas.parentesco },
            { chave: 'telefone', titulo: T.referencias.colunas.telefone },
            { chave: 'acoes', titulo: T.referencias.colunas.acoes },
          ]}
          linhas={linhas}
          textoVazio={T.referencias.vazio}
        />
      ) : null}
      {excluindo ? (
        <JanelaDeConfirmacao
          titulo={T.referencias.confirmarTitulo(texto(excluindo, 'nome'))}
          rotuloConfirmar={T.referencias.excluir}
          rotuloCancelar={T.confirmar.voltar}
          perigosa
          ocupado={executando}
          aoCancelar={() => setExcluindo(null)}
          aoConfirmar={() => void excluir()}
        >
          {T.referencias.confirmarTexto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
