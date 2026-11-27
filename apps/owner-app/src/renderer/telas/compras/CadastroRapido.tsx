import { Aviso, Botao, Campo } from '@corona/ui';
import { useRef, useState, type FormEvent } from 'react';
import { TIPOS_DE_CODIGO, type TipoDeCodigo } from '../../../shared/produtos';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { FaltaDeCadastro } from '../../componentes/FaltaDeCadastro';
import { TEXTOS } from '../../textos';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { avaliarCodigo, normalizarCodigo, tipoSugerido } from '../../nucleo/codigos';
import { lerReaisDigitados } from '../../nucleo/moedas';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { lerOpcoes, lerProduto, type Produto } from '../produtos/dados';

const T = TEXTOS_DE_COMPRAS.rapido;

/** Os campos recusados que o cadastro rápido mostra junto do próprio campo (o aviso não repete o motivo). */
const CAMPOS_DO_CADASTRO_RAPIDO = [
  'codigos[0].codigo',
  'nome',
  'categoriaId',
  'precoVenda',
  'precoCusto',
];

export interface CadastroRapidoProps {
  ponte: PonteDoCorona;
  /** O código que foi lido e não existe. */
  codigo: string;
  /** Fornecedor da compra: serve de dono do código quando ele é "do fornecedor". */
  fornecedorId: string;
  /** Custo que a planilha ou a tela já trazem (texto com vírgula), se houver. */
  custoInicial?: string;
  aoCadastrar: (produto: Produto) => void;
  aoCancelar: () => void;
}

/** Mini-formulário para cadastrar a peça na hora, quando o código lido não existe. */
export function CadastroRapido({
  ponte,
  codigo,
  fornecedorId,
  custoInicial = '',
  aoCadastrar,
  aoCancelar,
}: CadastroRapidoProps) {
  const [codigoDigitado, setCodigoDigitado] = useState(normalizarCodigo(codigo));
  const [tipo, setTipo] = useState<TipoDeCodigo>(tipoSugerido(codigo, 'LOJA'));
  const [nome, setNome] = useState('');
  const [categoriaId, setCategoriaId] = useState('');
  const [venda, setVenda] = useState('');
  const [custo, setCusto] = useState(custoInicial);
  const [tentou, setTentou] = useState(false);
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  // O mesmo identificador em toda tentativa: reenviar depois de uma falha de rede não cadastra duas vezes.
  const idDoProduto = useRef(crypto.randomUUID());
  const trava = useRef(false);

  const categorias = useBusca(
    () => chamarTodasAsPaginas(ponte, 'categorias.listar', { situacao: 'ATIVOS' }),
    'categorias-do-cadastro-rapido',
  );
  const listaDeCategorias = categorias.dados ? lerOpcoes(categorias.dados) : [];

  const vendaLida = lerReaisDigitados(venda);
  const custoLido = lerReaisDigitados(custo);
  const avisoDoCodigo = avaliarCodigo(codigoDigitado, tipo);
  const campo = (nomeDoCampo: string) => erro?.campos[nomeDoCampo];
  const obrigatorio = (vazio: boolean) => (tentou && vazio ? T.obrigatorio : undefined);
  const erroDoCodigo =
    avisoDoCodigo?.nivel === 'erro'
      ? avisoDoCodigo.mensagem
      : (obrigatorio(normalizarCodigo(codigoDigitado) === '') ?? campo('codigos[0].codigo'));
  const erroDoNome = tentou && nome.trim() === '' ? T.nomeAviso : campo('nome');

  async function salvar(e: FormEvent) {
    e.preventDefault();
    if (trava.current) return;
    setTentou(true);
    const lido = normalizarCodigo(codigoDigitado);
    if (
      lido === '' ||
      avisoDoCodigo?.nivel === 'erro' ||
      nome.trim() === '' ||
      !categoriaId ||
      vendaLida === null ||
      custoLido === null
    ) {
      return;
    }
    trava.current = true;
    setSalvando(true);
    setErro(null);
    const r = await chamar(ponte, 'produtos.criar', {
      id: idDoProduto.current,
      nome: nome.trim(),
      categoriaId,
      precoVenda: vendaLida,
      precoCusto: custoLido,
      ...(tipo === 'FORNECEDOR' && fornecedorId ? { fornecedorId } : {}),
      codigos: [
        {
          codigo: lido,
          tipo,
          principal: true,
          ...(tipo === 'FORNECEDOR' && fornecedorId ? { fornecedorId } : {}),
        },
      ],
    });
    trava.current = false;
    setSalvando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const produto = lerProduto(r.dados);
    if (produto) aoCadastrar(produto);
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-cadastro-rapido">
      <h3 id="titulo-cadastro-rapido" className="gestao-subtitulo">
        {T.titulo}
      </h3>
      <p className="gestao-texto">{T.ajuda(codigo)}</p>
      <form className="gestao-formulario" onSubmit={(e) => void salvar(e)} noValidate>
        <div className="cs-campo">
          <label htmlFor="rapido-tipo">{T.tipo}</label>
          <select
            id="rapido-tipo"
            value={tipo}
            onChange={(e) => setTipo(e.target.value as TipoDeCodigo)}
          >
            {TIPOS_DE_CODIGO.map((t) => (
              <option key={t} value={t}>
                {T.tipos[t]}
              </option>
            ))}
          </select>
        </div>
        <Campo
          id="rapido-codigo"
          rotulo={T.codigo}
          value={codigoDigitado}
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => setCodigoDigitado(e.target.value)}
          {...(erroDoCodigo ? { erro: erroDoCodigo } : {})}
        />
        {avisoDoCodigo?.nivel === 'aviso' ? (
          <Aviso tipo="atencao">{avisoDoCodigo.mensagem}</Aviso>
        ) : null}
        <Campo
          id="rapido-nome"
          rotulo={T.nome}
          value={nome}
          autoComplete="off"
          maxLength={200}
          autoFocus
          onChange={(e) => setNome(e.target.value)}
          {...(erroDoNome ? { erro: erroDoNome } : {})}
        />
        {categorias.dados !== null && listaDeCategorias.length === 0 ? (
          <FaltaDeCadastro
            mensagem={TEXTOS.faltaCadastro.semCategoria}
            rotuloDoBotao={TEXTOS.faltaCadastro.cadastrarCategoria}
            rota="cadastros/categorias"
          />
        ) : null}
        <div className={`cs-campo${tentou && !categoriaId ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="rapido-categoria">{T.categoria}</label>
          <select
            id="rapido-categoria"
            value={categoriaId}
            onChange={(e) => setCategoriaId(e.target.value)}
          >
            <option value="">{T.escolhaCategoria}</option>
            {listaDeCategorias.map((c) => (
              <option key={c.id} value={c.id}>
                {c.nome}
              </option>
            ))}
          </select>
          {tentou && !categoriaId ? (
            <span role="alert" className="cs-campo__erro">
              {T.escolhaCategoriaAviso}
            </span>
          ) : null}
          {campo('categoriaId') ? (
            <span role="alert" className="cs-campo__erro">
              {campo('categoriaId')}
            </span>
          ) : null}
        </div>
        <CampoDeDinheiro
          id="rapido-venda"
          rotulo={T.precoVenda}
          valor={venda}
          ajuda=""
          aoMudar={(digitado) => setVenda(digitado)}
          {...(tentou && venda.trim() === ''
            ? { erro: T.obrigatorio }
            : campo('precoVenda')
              ? { erro: campo('precoVenda')! }
              : {})}
        />
        <CampoDeDinheiro
          id="rapido-custo"
          rotulo={T.custo}
          valor={custo}
          ajuda=""
          aoMudar={(digitado) => setCusto(digitado)}
          {...(tentou && custo.trim() === ''
            ? { erro: T.obrigatorio }
            : campo('precoCusto')
              ? { erro: campo('precoCusto')! }
              : {})}
        />
        {erro ? <AvisoDeErro erro={erro} camposJaMostrados={CAMPOS_DO_CADASTRO_RAPIDO} /> : null}
        <div className="gestao-acoes-da-linha">
          <Botao onClick={aoCancelar} disabled={salvando}>
            {T.cancelar}
          </Botao>
          <Botao type="submit" variante="primario" disabled={salvando}>
            {salvando ? T.salvando : T.salvar}
          </Botao>
        </div>
      </form>
    </section>
  );
}
