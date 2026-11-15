import { Botao } from '@corona/ui';
import { useRef, useState } from 'react';
import { FORMAS_DO_FINANCEIRO } from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha, useVendedores } from '../devolucoes/Apoio';
import { soReal } from './Valor';
import { erroDoFinanceiro } from './dados';
import { lerContasAReceber } from './recebimentos';

const T = TEXTOS_DE_FINANCEIRO.recebimentos.novo;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

/** Recebimento avulso do vendedor, fora do acerto. O id nasce com o formulário e vale para toda nova tentativa. */
export function RegistrarRecebimento({
  ponte,
  aoFeito,
  aoCancelar,
}: {
  ponte: PonteDoCorona;
  aoFeito: (jaRegistrado: boolean) => void;
  aoCancelar: () => void;
}) {
  const comandoId = useRef(crypto.randomUUID());
  const [vendedorId, setVendedorId] = useState('');
  const [contaId, setContaId] = useState('');
  const [forma, setForma] = useState<string>('DINHEIRO');
  const [digitado, setDigitado] = useState('');
  const [valor, setValor] = useState<string | null>(null);
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);
  const vendedores = useVendedores(ponte);
  const contasBusca = useBusca(
    () =>
      vendedorId
        ? chamar(ponte, 'contasAReceber.listar', {
            vendedorId,
            nivel: 'VENDEDOR',
            situacao: 'EM_ABERTO',
          })
        : Promise.resolve({ ok: true as const, dados: [] }),
    `contas-do-vendedor-${vendedorId}`,
  );
  const contas = contasBusca.dados ? lerContasAReceber(contasBusca.dados) : [];

  async function registrar() {
    setTentou(true);
    if (trava.current || !vendedorId || valor === null) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'recebimentos.receber', {
      id: comandoId.current,
      vendedorId,
      forma,
      valor,
      ...(contaId ? { contaId } : {}),
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    aoFeito(r.jaRegistrado === true);
  }

  return (
    <form
      className="gestao-formulario gestao-formulario--larga"
      onSubmit={(e) => {
        e.preventDefault();
        void registrar();
      }}
    >
      <h2 className="gestao-subtitulo">{T.titulo}</h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <CampoDeEscolha
        id="recebimento-vendedor"
        rotulo={T.vendedor}
        valor={vendedorId}
        aoMudar={(v) => {
          setVendedorId(v);
          setContaId('');
        }}
        opcoes={vendedores.opcoes}
        vazio={T.escolhaVendedor}
      />
      {tentou && !vendedorId ? <p className="cs-campo__erro">{T.avisoVendedor}</p> : null}
      {vendedorId ? (
        <CampoDeEscolha
          id="recebimento-conta"
          rotulo={T.conta}
          valor={contaId}
          aoMudar={setContaId}
          opcoes={contas.map((c) => ({
            valor: c.id,
            texto: T.contaDe(dataParaTela(c.vencimento), soReal(c.emAberto)),
          }))}
          vazio={T.todasAsContas}
        />
      ) : null}
      <CampoDeEscolha
        id="recebimento-forma"
        rotulo={T.forma}
        valor={forma}
        aoMudar={setForma}
        opcoes={FORMAS_DO_FINANCEIRO.map((f) => ({ valor: f, texto: FORMAS[f] ?? f }))}
      />
      <CampoDeDinheiro
        id="recebimento-valor"
        rotulo={T.valor}
        valor={digitado}
        {...(tentou && valor === null ? { erro: T.avisoValor } : {})}
        aoMudar={(d, v) => {
          setDigitado(d);
          setValor(v !== null && Number(v) > 0 ? v : null);
        }}
      />
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao type="submit" variante="primario" disabled={ocupado}>
          {T.salvar}
        </Botao>
        <Botao onClick={aoCancelar} disabled={ocupado}>
          {T.cancelar}
        </Botao>
      </div>
    </form>
  );
}
