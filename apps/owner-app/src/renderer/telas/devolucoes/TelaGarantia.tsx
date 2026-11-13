import { Aviso, Botao, Campo } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { lerGarantia, type Garantia } from './dados';

const T = TEXTOS_DE_DEVOLUCOES.garantia;

/** "500.00" → "500,00": o campo de dinheiro mostra do jeito que a pessoa digita. */
const paraDigitar = (v: string | null) => (v === null ? '' : v.replace('.', ','));

function dias(digitado: string): number | null {
  const t = digitado.trim();
  if (!/^\d{1,3}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= 365 ? n : null;
}

function porcentagem(digitado: string): number | null {
  const t = digitado.trim();
  if (!/^\d{1,3}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= 100 ? n : null;
}

function Formulario({ ponte, inicial }: { ponte: PonteDoCorona; inicial: Garantia }) {
  const [desistir, setDesistir] = useState(String(inicial.prazoArrependimentoDias));
  const [defeito, setDefeito] = useState(String(inicial.prazoDefeitoDias));
  const [percentual, setPercentual] = useState(String(inicial.percentualAlertaDevolucao));
  const [semLimite, setSemLimite] = useState(inicial.limiteReembolsoSemanal === null);
  const [limite, setLimite] = useState(paraDigitar(inicial.limiteReembolsoSemanal));
  const [limiteEmReais, setLimiteEmReais] = useState<string | null>(inicial.limiteReembolsoSemanal);
  const [semAprovacao, setSemAprovacao] = useState(inicial.valorAprovacaoReembolso === null);
  const [aprovacao, setAprovacao] = useState(paraDigitar(inicial.valorAprovacaoReembolso));
  const [aprovacaoEmReais, setAprovacaoEmReais] = useState<string | null>(
    inicial.valorAprovacaoReembolso,
  );
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [salva, setSalva] = useState(false);
  const trava = useRef(false);

  const valorDoLimite = semLimite ? null : limiteEmReais;
  const valorDaAprovacao = semAprovacao ? null : aprovacaoEmReais;
  const limiteRuim = !semLimite && (limiteEmReais === null || Number(limiteEmReais) <= 0);
  const aprovacaoRuim =
    !semAprovacao && (aprovacaoEmReais === null || Number(aprovacaoEmReais) <= 0);

  async function salvar() {
    if (trava.current) return;
    setTentou(true);
    setSalva(false);
    const d = dias(desistir);
    const f = dias(defeito);
    const p = porcentagem(percentual);
    if (d === null || f === null || p === null || limiteRuim || aprovacaoRuim) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'garantia.alterar', {
      prazoArrependimentoDias: d,
      prazoDefeitoDias: f,
      limiteReembolsoSemanal: valorDoLimite,
      valorAprovacaoReembolso: valorDaAprovacao,
      percentualAlertaDevolucao: p,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) setErro(r.erro);
    else setSalva(true);
  }

  return (
    <form
      className="gestao-formulario"
      onSubmit={(e) => {
        e.preventDefault();
        void salvar();
      }}
    >
      <Campo
        rotulo={T.diasParaDesistir}
        value={desistir}
        inputMode="numeric"
        autoComplete="off"
        ajuda={T.diasParaDesistirAjuda}
        onChange={(e) => setDesistir(e.target.value)}
        {...(tentou && dias(desistir) === null ? { erro: T.erroDias } : {})}
      />
      <Campo
        rotulo={T.diasDoDefeito}
        value={defeito}
        inputMode="numeric"
        autoComplete="off"
        onChange={(e) => setDefeito(e.target.value)}
        {...(tentou && dias(defeito) === null ? { erro: T.erroDias } : {})}
      />
      <div>
        <CampoDeDinheiro
          id="garantia-limite"
          rotulo={T.limiteSemanal}
          valor={limite}
          desabilitado={semLimite}
          ajuda=""
          aoMudar={(digitado, emReais) => {
            setLimite(digitado);
            setLimiteEmReais(emReais);
          }}
          {...(tentou && limiteRuim ? { erro: T.erroValor } : {})}
        />
        <div className="cs-campo">
          <label htmlFor="garantia-sem-limite">
            <input
              id="garantia-sem-limite"
              type="checkbox"
              checked={semLimite}
              onChange={(e) => setSemLimite(e.target.checked)}
            />{' '}
            {T.semLimite}
          </label>
        </div>
      </div>
      <div>
        <CampoDeDinheiro
          id="garantia-aprovacao"
          rotulo={T.valorDeAprovacao}
          valor={aprovacao}
          desabilitado={semAprovacao}
          ajuda=""
          aoMudar={(digitado, emReais) => {
            setAprovacao(digitado);
            setAprovacaoEmReais(emReais);
          }}
          {...(tentou && aprovacaoRuim ? { erro: T.erroValor } : {})}
        />
        <div className="cs-campo">
          <label htmlFor="garantia-sem-aprovacao">
            <input
              id="garantia-sem-aprovacao"
              type="checkbox"
              checked={semAprovacao}
              onChange={(e) => setSemAprovacao(e.target.checked)}
            />{' '}
            {T.naoAprovarAntes}
          </label>
        </div>
      </div>
      <Campo
        rotulo={T.percentual}
        value={percentual}
        inputMode="numeric"
        autoComplete="off"
        ajuda={T.percentualAjuda}
        onChange={(e) => setPercentual(e.target.value)}
        {...(tentou && porcentagem(percentual) === null ? { erro: T.erroPorcentagem } : {})}
      />
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {salva ? <Aviso tipo="sucesso">{T.salva}</Aviso> : null}
      <div className="gestao-acoes-da-linha">
        <Botao type="submit" variante="primario" disabled={ocupado}>
          {ocupado ? T.salvando : T.salvar}
        </Botao>
      </div>
    </form>
  );
}

/** Garantia e limites das devoluções: dias para desistir e para o defeito, limite da semana, valor de aprovação e aviso. */
export function TelaGarantia({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'garantia.ler'), 'garantia');
  const garantia = busca.dados ? lerGarantia(busca.dados) : null;
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {garantia ? <Formulario ponte={ponte} inicial={garantia} /> : null}
    </div>
  );
}
