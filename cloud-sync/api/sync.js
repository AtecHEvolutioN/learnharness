import {put,get,list} from '@vercel/blob';
import {createHandler} from '../lib/handler.js';
export async function POST(request){return createHandler({put,get,list},process.env.SYNC_TOKEN_SHA256)(request);}
export function GET(){return Response.json({service:'LearnHarness private sync',version:1},{headers:{'Cache-Control':'no-store'}});}
