import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import { TIPOS_DO_CAIXA } from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { erroDoFinanceiro, lerCategorias, type CategoriaDoCaixa } from './dados';

const T = TEXTOS_DE_FINANCEIRO.categorias;

type Formulario = { modo: 'nova' } | { modo: 'renomear'; categoria: CategoriaDoCaixa };

/** Um formulário de nome (e de tipo, só ao criar). O id da categoria nova nasce com o formulário. */
function FormularioDaCategoria({
  ponte,
  formulario,
  aoFeito,
  aoCancelar,
}: {
  ponte: PonteDoCorona;
  formulario: Formulario;
  aoFeito: (aviso: string) => void;
  aoCancelar: () => void;
}) {
  const novoId = useRef(crypto.randomUUID());
  const [nome, setNome] = useState(formulario.modo === 'renomear' ? formulario.categoria.nome : '');
  const [tipo, setTipo] = useState<string>('SAIDA');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  async function salvar() {
    setTentou(true);
    if (trava.current || nome.trim() === '') return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r =
      formulario.modo === 'nova'
        ? await chamar(ponte, 'caixa.criarCategoria', {
            id: novoId.current,
            nome: nome.trim(),
            tipo,
          })
        : await chamar(ponte, 'caixa.renomearCategoria', {
            id: formulario.categoria.id,
            nome: nome.trim(),
          });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    aoFeito(formulario.modo === 'nova' ? T.criada : T.renomeada);
  }

  return (
    <form
      className="gestao-formulario gestao-formulario--larga"
      onSubmit={(e) => {
        e.preventDefault();
        void salvar();
      }}
    >
      <h2 className="gestao-subtitulo">
        {formulario.modo === 'nova' ? T.nova : T.renomearDe(formulario.categoria.nome)}
      </h2>
      <Campo
        rotulo={T.nome}
        value={nome}
        maxLength={80}
        autoComplete="off"
        {...(tentou && nome.trim() === '' ? { erro: T.avisoNome } : {})}
        onChange={(e) => setNome(e.target.value)}
      />
      {formulario.modo === 'nova' ? (
        <CampoDeEscolha
          id="categoria-tipo"
          rotulo={T.tipo}
          valor={tipo}
          aoMudar={setTipo}
          opcoes={TIPOS_DO_CAIXA.map((t) => ({
            valor: t,
            texto: TEXTOS_DE_FINANCEIRO.tipos[t] ?? t,
          }))}
        />
      ) : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao type="submit" variante="primario" disabled={ocupado}>
          {formulario.modo === 'nova' ? T.criar : T.salvar}
        </Botao>
        <Botao onClick={aoCancelar} disabled={ocupado}>
          {T.cancelar}
        </Botao>
      </div>
    </form>
  );
}

/** Financeiro → Categorias do caixa: criar, renomear, inativar e reativar (não se exclui; o tipo não muda). */
export function TelaCategoriasDoCaixa({ ponte }: { ponte: PonteDoCorona }) {
  const [volta, setVolta] = useState(0);
  const [formulario, setFormulario] = useState<Formulario | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const busca = useBusca(() => chamar(ponte, 'caixa.categorias'), `categorias-${volta}`);
  const categorias = busca.dados ? lerCategorias(busca.dados) : [];

  async function mudarSituacao(c: CategoriaDoCaixa, ativar: boolean) {
    setErro(null);
    setAviso(null);
    const r = await chamar(ponte, ativar ? 'caixa.reativarCategoria' : 'caixa.inativarCategoria', {
      id: c.id,
    });
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    setAviso(ativar ? T.reativada : T.inativada);
    setVolta((v) => v + 1);
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        {!formulario ? (
          <Botao
            variante="primario"
            onClick={() => {
              setAviso(null);
              setFormulario({ modo: 'nova' });
            }}
          >
            {T.nova}
          </Botao>
        ) : null}
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {formulario ? (
        <FormularioDaCategoria
          key={formulario.modo === 'nova' ? 'nova' : formulario.categoria.id}
          ponte={ponte}
          formulario={formulario}
          aoCancelar={() => setFormulario(null)}
          aoFeito={(texto) => {
            setFormulario(null);
            setAviso(texto);
            setVolta((v) => v + 1);
          }}
        />
      ) : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazia}
        colunas={[
          { chave: 'nome', titulo: T.colunas.nome },
          { chave: 'tipo', titulo: T.colunas.tipo },
          { chave: 'situacao', titulo: T.colunas.situacao },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={categorias.map((c) => ({
          nome: c.nome,
          tipo: TEXTOS_DE_FINANCEIRO.tipos[c.tipo] ?? c.tipo,
          situacao: c.sistema ? T.doSistema : c.ativa ? T.ativa : T.inativa,
          acoes: c.sistema ? (
            ''
          ) : (
            <span className="gestao-acoes-da-linha">
              <Botao
                aria-label={T.renomearDe(c.nome)}
                onClick={() => setFormulario({ modo: 'renomear', categoria: c })}
              >
                {T.renomear}
              </Botao>
              {c.ativa ? (
                <Botao
                  aria-label={T.inativarDe(c.nome)}
                  onClick={() => void mudarSituacao(c, false)}
                >
                  {T.inativar}
                </Botao>
              ) : (
                <Botao
                  aria-label={T.reativarDe(c.nome)}
                  onClick={() => void mudarSituacao(c, true)}
                >
                  {T.reativar}
                </Botao>
              )}
            </span>
          ),
        }))}
      />
    </div>
  );
}
