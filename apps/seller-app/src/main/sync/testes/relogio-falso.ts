import type { Relogio } from '../../tipos';

/** Relógio manual para os testes da agenda: o tempo só anda quando o teste manda. */
export class RelogioFalso implements Relogio {
  private atual = 0;
  private proximoId = 0;
  private readonly tarefas = new Map<number, { quando: number; fn: () => void }>();

  agora(): number {
    return this.atual;
  }

  agendar(ms: number, fn: () => void): () => void {
    const id = this.proximoId++;
    this.tarefas.set(id, { quando: this.atual + ms, fn });
    return () => {
      this.tarefas.delete(id);
    };
  }

  /** Esperas agendadas, em ms a partir de agora. */
  pendentes(): number[] {
    return [...this.tarefas.values()].map((t) => t.quando - this.atual).sort((a, b) => a - b);
  }

  /** Anda o tempo, rodando o que vencer (e deixando as promessas resolverem entre uma e outra). */
  async andar(ms: number): Promise<void> {
    const fim = this.atual + ms;
    for (;;) {
      const [id, t] =
        [...this.tarefas.entries()]
          .filter(([, x]) => x.quando <= fim)
          .sort((a, b) => a[1].quando - b[1].quando)[0] ?? [];
      if (id === undefined || !t) break;
      this.tarefas.delete(id);
      this.atual = t.quando;
      t.fn();
      await assentar();
    }
    this.atual = fim;
    await assentar();
  }
}

export async function assentar(): Promise<void> {
  for (let i = 0; i < 10; i++) await Promise.resolve();
}
