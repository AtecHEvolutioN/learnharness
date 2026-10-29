import { projectView } from '../../src/core/view.ts';
import { pendingQuestionOf, activeProblemOf, needsContinuation } from '../../src/core/native-messages.ts';
import { toolQuestionKind } from '../../src/core/pending.ts';
import { buildAnswer, normalizeOptions, resolveCorrect, seededRandom, shuffleOptions, toClientQuestion } from '../../src/core/question-core.ts';

export function questionData(pending: any) {
  const input = pending.input;
  const kind = toolQuestionKind(pending.tool);
  const options = normalizeOptions(input.options ?? []);
  const ordered = kind === 'quiz' ? shuffleOptions(options, seededRandom(pending.toolCallId)) : options;
  const correct = kind === 'quiz' ? resolveCorrect(input.correctAnswer, ordered) : null;
  if (correct?.error) throw new Error('Desktop question could not be graded. Open it on your PC.');
  return { id: pending.toolCallId, kind, question: input.question, context: input.details,
    mode: (kind === 'explain' || !ordered.length) ? 'text' : input.multiSelect ? 'multi-select' : 'single-select',
    options: ordered, correctIndices: correct?.indices, committedAnswer: kind === 'explain' ? input.correctAnswer : undefined,
    explanation: input.explanation, createdAt: new Date().toISOString() } as any;
}

export function mobileInput(record: any, input: any) {
  if (!input || typeof input !== 'object') throw new Error('Missing input');
  const pending = pendingQuestionOf(record.messages);
  const problem = activeProblemOf(record.messages);
  if (input.type === 'answer') {
    if (!pending || pending.toolCallId !== input.toolCallId) throw new Error('This question changed. Refresh the session.');
    const qd = questionData(pending);
    const request = input.answer ?? {};
    if (!Array.isArray(request.values ?? [])) throw new Error('Invalid answer options');
    if ((request.values ?? []).some((v: string) => !qd.options.some((o: any) => o.value === v))) throw new Error('Unknown answer option');
    if (qd.mode === 'single-select' && (request.values ?? []).length > 1) throw new Error('Choose one option');
    for (const key of ['text', 'thinking', 'other']) if (request[key] !== undefined && (typeof request[key] !== 'string' || request[key].length > 12000)) throw new Error('Invalid answer text');
    if (request.confidence !== undefined && (!Number.isFinite(request.confidence) || request.confidence < 0 || request.confidence > 100)) throw new Error('Invalid confidence');
    if (!request.dontKnow && !(request.values?.length || request.text?.trim() || request.other?.trim())) throw new Error('Enter an answer');
    return { type: 'answer', toolCallId: pending.toolCallId, answer: buildAnswer(qd, request, pending.tool) };
  }
  if (input.type === 'text') {
    if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 12000) throw new Error('Enter a message up to 12000 characters');
    if (pending) throw new Error('Answer the current question first');
    return problem ? {type: 'thinking', problemCallId: problem.toolCallId, text: input.text} : {type: 'text', text: input.text};
  }
  if (input.type === 'next' && record.kind === 'practice' && !problem && !pending) return {type: 'next'};
  if (input.type === 'retry' && record.turnError) return {type: 'retry'};
  if (input.type === 'continue' && needsContinuation(record.messages)) return {type: 'continue'};
  throw new Error('This action is not available. Refresh the session.');
}

export function projectSession(record: any) {
  const pending = pendingQuestionOf(record.messages);
  const problem = activeProblemOf(record.messages);
  const messages = projectView(record.messages).map((m: any) => {
    const texts: string[] = [];
    for (const p of m.parts) {
      if (p.type === 'text' && p.text) texts.push(p.text);
      if (p.type.startsWith('tool-') && p.state === 'output-available' && ['tool-quiz','tool-probe','tool-recall','tool-explain','tool-ask_user_question'].includes(p.type)) {
        const a = p.output;
        if (a) texts.push([a.questionText, a.dontKnow ? "I don't know" : a.text || a.other || a.answers?.map((x: any) => x.label).join(', '), a.thinking, a.explanation, a.committed].filter(Boolean).join('\n\n'));
      }
      if (p.type === 'tool-conclude' && p.state === 'output-available') texts.push([p.input?.verdict, p.input?.outcome, p.reveal].filter(Boolean).join('\n\n'));
      if (p.type === 'tool-problem' && p.input?.statement) texts.push(p.input.statement);
    }
    return {id: m.id, role: m.role, text: texts.join('\n\n')};
  }).filter((m: any) => m.text);
  const plan = (record.tree?.nodes ?? []).map((n: any) => ({id: n.id, title: n.title, summary: n.summary ?? '', prerequisites: (record.tree?.edges ?? []).filter((e: any) => e.to === n.id).map((e: any) => e.from)}));
  return { id: record.id, title: record.title, mode: record.kind === 'practice' ? 'practice' : 'learn',
    createdAt: Date.parse(record.createdAt) || 0, messages, plan, busy: record.turnInFlight === true,
    error: record.turnError || null, needsContinuation: needsContinuation(record.messages),
    question: pending ? toClientQuestion(questionData(pending)) : null,
    problem: problem ? {id: problem.toolCallId, statement: (problem.input as any)?.statement ?? ''} : null };
}
