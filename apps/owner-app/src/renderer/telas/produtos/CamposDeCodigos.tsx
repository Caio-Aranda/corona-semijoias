import { Aviso, Botao, Campo } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TIPOS_DE_CODIGO, type TipoDeCodigo } from '../../../shared/produtos';
import { chamar } from '../../nucleo/chamadas';
import {
  DetectorDeLeitor,
  avaliarCodigo,
  normalizarCodigo,
  tipoSugerido,
} from '../../nucleo/codigos';
import { TEXTOS } from '../../textos';
import { lerProduto, lerProximoCodigo } from './dados';

const T = TEXTOS.produtos.codigos;

export interface LinhaDeCodigo {
  /** Chave só da tela. */
  chave: string;
  /** Id do código quando ele já existe no servidor. */
  id?: string;
  codigo: string;
  tipo: TipoDeCodigo;
  fornecedorId: string;
  principal: boolean;
}

export const novaLinhaDeCodigo = (parcial: Partial<LinhaDeCodigo> = {}): LinhaDeCodigo => ({
  chave: crypto.randomUUID(),
  codigo: '',
  tipo: 'LOJA',
  fornecedorId: '',
  principal: false,
  ...parcial,
});

interface Duplicado {
  id: string;
  nome: string;
}

export interface CamposDeCodigosProps {
  ponte: PonteDoCorona;
  linhas: LinhaDeCodigo[];
  aoMudar: (linhas: LinhaDeCodigo[]) => void;
  fornecedores: { id: string; nome: string }[];
  /** Produto que está sendo editado (o próprio produto não conta como duplicidade). */
  produtoId: string | null;
  /** Quando a pessoa pede para abrir o produto que já usa o código. */
  aoAbrirProduto: (id: string) => void;
  /** Mensagem do servidor ou da conferência da lista inteira. */
  erro?: string;
  /** Cursor já em Código: a tela de cadastro abre assim (§8). */
  focoAutomatico?: boolean;
}

/**
 * Os códigos da peça: leitor que age como teclado (Enter envia), conferência de duplicidade na hora,
 * vários códigos por peça com um principal e o botão de gerar o código da loja.
 */
export function CamposDeCodigos({
  ponte,
  linhas,
  aoMudar,
  fornecedores,
  produtoId,
  aoAbrirProduto,
  erro,
  focoAutomatico,
}: CamposDeCodigosProps) {
  const detectores = useRef(new Map<string, DetectorDeLeitor>());
  const [duplicados, setDuplicados] = useState<Record<string, Duplicado | null>>({});
  const [lidos, setLidos] = useState<Record<string, boolean>>({});
  const [conferindo, setConferindo] = useState<Record<string, boolean>>({});
  const [gerando, setGerando] = useState(false);
  const [avisoGerado, setAvisoGerado] = useState<string | null>(null);
  const [erroAoGerar, setErroAoGerar] = useState<string | null>(null);

  const detector = (chave: string) => {
    let d = detectores.current.get(chave);
    if (!d) detectores.current.set(chave, (d = new DetectorDeLeitor()));
    return d;
  };

  const trocar = (chave: string, mudanca: Partial<LinhaDeCodigo>) =>
    aoMudar(linhas.map((l) => (l.chave === chave ? { ...l, ...mudanca } : l)));

  const conferirDuplicidade = async (linha: LinhaDeCodigo) => {
    const codigo = normalizarCodigo(linha.codigo);
    if (codigo === '') return;
    setConferindo((c) => ({ ...c, [linha.chave]: true }));
    const r = await chamar(ponte, 'produtos.porCodigo', { codigo });
    setConferindo((c) => ({ ...c, [linha.chave]: false }));
    const achado = r.ok ? lerProduto(r.dados) : null;
    setDuplicados((d) => ({
      ...d,
      [linha.chave]:
        achado && achado.id !== produtoId ? { id: achado.id, nome: achado.nome } : null,
    }));
  };

  const gerarCodigoDaLoja = async () => {
    setGerando(true);
    setErroAoGerar(null);
    setAvisoGerado(null);
    const r = await chamar(ponte, 'produtos.codigoProximo');
    setGerando(false);
    if (!r.ok) {
      setErroAoGerar(r.erro.mensagem);
      return;
    }
    const codigo = lerProximoCodigo(r.dados);
    if (!codigo) return;
    const vazia = linhas.find((l) => normalizarCodigo(l.codigo) === '');
    const nova = novaLinhaDeCodigo({
      codigo,
      tipo: 'LOJA',
      principal: !linhas.some((l) => l.principal),
    });
    if (vazia) {
      aoMudar(linhas.map((l) => (l === vazia ? { ...vazia, codigo, tipo: 'LOJA' } : l)));
    } else aoMudar([...linhas, nova]);
    setAvisoGerado(T.gerado(codigo));
  };

  const tirar = (linha: LinhaDeCodigo) => {
    const resto = linhas.filter((l) => l.chave !== linha.chave);
    // Quem era o principal passa o posto ao primeiro que sobrou.
    if (linha.principal && resto[0]) resto[0] = { ...resto[0], principal: true };
    aoMudar(resto.length > 0 ? resto : [novaLinhaDeCodigo({ principal: true })]);
  };

  return (
    <fieldset className="gestao-grupo">
      <legend>{T.titulo}</legend>
      <p className="gestao-texto">{T.ajuda}</p>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      {linhas.map((l, i) => {
        const aviso = avaliarCodigo(l.codigo, l.tipo);
        const duplicado = duplicados[l.chave];
        const idCampo = `codigo-${l.chave}`;
        return (
          <div
            key={l.chave}
            className="gestao-codigo"
            role="group"
            aria-label={`${T.campo} ${i + 1}`}
          >
            <Campo
              id={idCampo}
              rotulo={T.campo}
              value={l.codigo}
              autoComplete="off"
              spellCheck={false}
              {...(focoAutomatico && i === 0 ? { autoFocus: true } : {})}
              {...(aviso?.nivel === 'erro' ? { erro: aviso.mensagem } : {})}
              {...(aviso?.nivel === 'aviso' ? { ajuda: aviso.mensagem } : {})}
              onChange={(e) => {
                const codigo = e.target.value;
                setLidos((x) => ({ ...x, [l.chave]: false }));
                setDuplicados((x) => ({ ...x, [l.chave]: null }));
                trocar(l.chave, { codigo });
              }}
              onKeyDown={(e) => {
                if (e.key.length === 1 && !e.ctrlKey && !e.metaKey)
                  detector(l.chave).tecla(Date.now());
                if (e.key === 'Enter') {
                  // O leitor termina com Enter: não envia o formulário, só confere o código.
                  e.preventDefault();
                  const veioDoLeitor = detector(l.chave).foiLeitor();
                  detector(l.chave).reiniciar();
                  const codigo = normalizarCodigo(l.codigo);
                  const tipo = veioDoLeitor ? tipoSugerido(codigo, l.tipo) : l.tipo;
                  setLidos((x) => ({ ...x, [l.chave]: veioDoLeitor }));
                  if (codigo !== l.codigo || tipo !== l.tipo) trocar(l.chave, { codigo, tipo });
                  void conferirDuplicidade({ ...l, codigo });
                }
              }}
              onBlur={() => {
                if (!duplicados[l.chave]) void conferirDuplicidade(l);
              }}
            />
            {lidos[l.chave] ? <p className="gestao-texto">{T.lidoPeloLeitor}</p> : null}
            {conferindo[l.chave] ? (
              <p className="gestao-texto" role="status">
                {T.verificando}
              </p>
            ) : null}
            {duplicado ? (
              <Aviso tipo="atencao">
                {`${T.jaExiste(duplicado.nome)} ${l.tipo === 'FORNECEDOR' ? T.fornecedorRepetido : ''}`.trim()}{' '}
                <Botao onClick={() => aoAbrirProduto(duplicado.id)}>{T.abrirExistente}</Botao>
              </Aviso>
            ) : null}
            <div className="cs-campo">
              <label htmlFor={`${idCampo}-tipo`}>{T.tipo}</label>
              <select
                id={`${idCampo}-tipo`}
                value={l.tipo}
                onChange={(e) => trocar(l.chave, { tipo: e.target.value as TipoDeCodigo })}
              >
                {TIPOS_DE_CODIGO.map((t) => (
                  <option key={t} value={t}>
                    {T.tipos[t]}
                  </option>
                ))}
              </select>
            </div>
            {l.tipo === 'FORNECEDOR' ? (
              <div className="cs-campo">
                <label htmlFor={`${idCampo}-fornecedor`}>{T.fornecedorDoCodigo}</label>
                <select
                  id={`${idCampo}-fornecedor`}
                  value={l.fornecedorId}
                  onChange={(e) => trocar(l.chave, { fornecedorId: e.target.value })}
                >
                  <option value="">{TEXTOS.produtos.ficha.semFornecedor}</option>
                  {fornecedores.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.nome}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            <label className="gestao-marcacao">
              <input
                type="radio"
                name="codigo-principal"
                checked={l.principal}
                aria-label={T.principalDe(l.codigo || String(i + 1))}
                onChange={() =>
                  aoMudar(linhas.map((x) => ({ ...x, principal: x.chave === l.chave })))
                }
              />{' '}
              {T.principal}
            </label>
            {linhas.length > 1 ? (
              <Botao aria-label={T.removerDe(l.codigo || String(i + 1))} onClick={() => tirar(l)}>
                {T.remover}
              </Botao>
            ) : null}
          </div>
        );
      })}
      <div className="gestao-acoes-da-linha">
        <Botao onClick={() => aoMudar([...linhas, novaLinhaDeCodigo()])}>{T.adicionar}</Botao>
        <Botao disabled={gerando} onClick={() => void gerarCodigoDaLoja()}>
          {gerando ? T.gerando : T.gerar}
        </Botao>
      </div>
      {avisoGerado ? <Aviso tipo="sucesso">{avisoGerado}</Aviso> : null}
      {erroAoGerar ? <Aviso tipo="erro">{erroAoGerar}</Aviso> : null}
    </fieldset>
  );
}
