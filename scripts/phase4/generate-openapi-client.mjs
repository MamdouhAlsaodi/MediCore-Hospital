#!/usr/bin/env node
// Deterministic typed TypeScript client generator (plan Task 10, T071; FR-017).
//
// Input : the OpenAPI 3.1 document in JSON form (argv[2]) — the exact same
//         document whose YAML form is tracked at api/openapi/medicore-v1.yaml.
// Output: a transport-agnostic typed client in the directory argv[3]:
//           schemas.ts     request/response component types
//           operations.ts  HttpRequest descriptor builders per operation
//           index.ts       re-exports
//
// The client is transport-agnostic on purpose: every operation returns an
// `HttpRequest` descriptor (method, fully-substituted path, optional JSON
// body) that the frontend's single shared `apiFetch` boundary executes. The
// generated module never calls fetch itself, so headers, error mapping, and
// session handling stay owned by the existing shared boundary (T073).
//
// Determinism contract (T070): components, properties, paths, and operations
// are emitted in fully sorted, fixed order; there are no timestamps, hashes,
// or any other run-dependent value in the output. Two runs over the same
// contract produce byte-identical files. Semantic drift is never hidden: any
// construct this generator does not understand fails the run instead of being
// approximated.
//
// Requiredness policy (documented, wire-verified): a property of a schema
// referenced as a SUCCESS RESPONSE is required unless it is explicitly
// nullable, because every demonstrated response is a strict DTO record whose
// components Jackson always serializes (nulls included). A property of a
// request-body schema follows the contract's own `required` list, which
// springdoc derives from the jakarta validation annotations.

import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const inputPath = resolve(process.argv[2] ?? fail('usage: generate-openapi-client.mjs <openapi.json> <outDir>'));
const outDir = resolve(process.argv[3] ?? fail('usage: generate-openapi-client.mjs <openapi.json> <outDir>'));

function fail(message) {
  console.error(`generate-openapi-client: ${message}`);
  process.exit(1);
}

const doc = JSON.parse(readFileSync(inputPath, 'utf8'));
const schemas = doc.components?.schemas ?? fail('document has no components.schemas');
const httpMethods = ['delete', 'get', 'post', 'put'];

// ---------------------------------------------------------------------------
// Type mapping
// ---------------------------------------------------------------------------

function refName(schema) {
  const ref = schema?.$ref;
  if (!ref) return null;
  const prefix = '#/components/schemas/';
  if (!ref.startsWith(prefix)) fail(`unsupported $ref target: ${ref}`);
  return ref.slice(prefix.length);
}

function isNullable(schema) {
  if (!schema) return false;
  if (schema.nullable === true) return true;
  if (Array.isArray(schema.type)) return schema.type.includes('null');
  return false;
}

function baseType(schema, context) {
  if (!schema || typeof schema !== 'object') fail('missing schema');
  const ref = refName(schema);
  if (ref) return ref;

  if (Array.isArray(schema.enum)) {
    return schema.enum.map((value) => JSON.stringify(value)).join(' | ');
  }
  if (schema.type === 'string' || schema.type === 'null') return 'string';
  if (Array.isArray(schema.type)) {
    const primary = schema.type.filter((t) => t !== 'null');
    if (primary.length !== 1) fail(`unsupported multi-type schema: ${JSON.stringify(schema.type)}`);
    if (primary[0] !== 'string') fail(`unsupported nullable compound type: ${JSON.stringify(schema.type)}`);
    return 'string';
  }
  if (schema.type === 'integer' || schema.type === 'number') return 'number';
  if (schema.type === 'boolean') return 'boolean';
  if (schema.type === 'array') {
    return `${baseType(schema.items, context)}[]`;
  }
  if (schema.type === 'object') {
    // No inline objects exist in the demonstrated contract; fail loudly so
    // new shapes cannot silently degrade into `unknown`.
    return fail(`inline object schemas are not supported: ${JSON.stringify(schema).slice(0, 120)}`);
  }
  return fail(`unsupported schema: ${JSON.stringify(schema).slice(0, 120)}`);
}

function typeOf(schema, context) {
  const type = baseType(schema, context);
  return isNullable(schema) ? `${type} | null` : type;
}

// ---------------------------------------------------------------------------
// Pass 1: which schemas are referenced from success responses (these get the
// documented response-presence requiredness policy).
// ---------------------------------------------------------------------------

const responseReferenced = new Set();
const usedSchemaNames = new Set();

for (const pathName of Object.keys(doc.paths ?? {}).sort()) {
  const pathItem = doc.paths[pathName];
  for (const method of httpMethods) {
    const operation = pathItem?.[method];
    if (!operation) continue;
    const response = successResponse(operation);
    collectResponseRefs(responseSchema(response));
  }
}

function collectResponseRefs(schema) {
  const name = refName(schema);
  if (name) {
    responseReferenced.add(name);
    return;
  }
  if (schema?.type === 'array') collectResponseRefs(schema.items);
}

// Expand transitively: a component referenced from a success response hands
// the same always-serialized presence policy to the components it is built
// from (arrays included), because every demonstrated DTO is a strict record.
function expandResponseRefs() {
  const queue = [...responseReferenced];
  while (queue.length > 0) {
    const name = queue.pop();
    const schema = schemas[name];
    if (!schema || schema.type !== 'object') continue;
    for (const property of Object.values(schema.properties ?? {})) {
      const ref = refName(property);
      if (ref) {
        if (!responseReferenced.has(ref)) {
          responseReferenced.add(ref);
          queue.push(ref);
        }
      } else if (property?.type === 'array') {
        const itemRef = refName(property.items);
        if (itemRef && !responseReferenced.has(itemRef)) {
          responseReferenced.add(itemRef);
          queue.push(itemRef);
        }
      }
    }
  }
}

expandResponseRefs();

function successResponse(operation) {
  const responses = operation.responses ?? {};
  for (const code of ['200', '201', '202', '204']) {
    if (responses[code]) return responses[code];
  }
  const twoXX = Object.keys(responses).filter((code) => /^2/.test(code)).sort();
  if (twoXX.length > 0) return responses[twoXX[0]];
  return null;
}

/** Springdoc derives un-annotated GET successes as the star-slash-star media type; prefer application/json. */
function responseSchema(response) {
  const content = response?.content;
  if (!content) return null;
  return content['application/json']?.schema ?? content['*/*']?.schema ?? null;
}

// ---------------------------------------------------------------------------
// schemas.ts
// ---------------------------------------------------------------------------

function requiredSet(name, schema) {
  const declared = new Set(schema.required ?? []);
  if (responseReferenced.has(name)) {
    // Response DTOs always serialize every component; only explicit
    // nullability can make a value absent-or-null.
    for (const [propName, prop] of Object.entries(schema.properties ?? {})) {
      if (!isNullable(prop)) declared.add(propName);
    }
  }
  return declared;
}

function emitInterface(name, schema) {
  if (schema.type !== 'object' || Array.isArray(schema.enum)) {
    const type = typeOf(schema, 'component');
    return `export type ${name} = ${type};\n`;
  }
  const properties = schema.properties ?? {};
  if (Array.isArray(schema.additionalProperties)) fail(`unsupported additionalProperties on ${name}`);
  const required = requiredSet(name, schema);
  const lines = Object.keys(properties).sort().map((propName) => {
    const optional = required.has(propName) ? '' : '?';
    return `  ${JSON.stringify(propName)}${optional}: ${typeOf(properties[propName], 'component')};`;
  });
  return `export interface ${name} {\n${lines.join('\n')}\n}\n`;
}

const schemaNames = Object.keys(schemas).sort();
const schemasTs = `// GENERATED FILE - DO NOT EDIT.
// Generated by scripts/phase4/generate-openapi-client.mjs from the OpenAPI
// contract api/openapi/medicore-v1.yaml (${doc.info?.title ?? 'MediCore Hospital API'} ${doc.info?.version ?? 'v1'}).
// Regenerate with: scripts/phase4/generate-openapi.sh — never hand-edit.

${schemaNames.map((name) => emitInterface(name, schemas[name])).join('\n')}`;

// ---------------------------------------------------------------------------
// operations.ts
// ---------------------------------------------------------------------------

function pathParamExpressions(operation, pathItemParams) {
  const params = [...(pathItemParams ?? []), ...(operation.parameters ?? [])];
  return params
    .filter((parameter) => parameter.in === 'path')
    .map((parameter) => ({
      name: parameter.name,
      tsName: tsIdentifier(parameter.name),
      schema: parameter.schema ?? {},
    }));
}

function queryParameters(operation, pathItemParams) {
  const params = [...(pathItemParams ?? []), ...(operation.parameters ?? [])]
    .filter((parameter) => parameter.in === 'query')
    .map((parameter) => ({
      name: parameter.name,
      schema: parameter.schema ?? {},
      required: parameter.required === true,
    }));
  return params;
}

function tsIdentifier(name) {
  if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) return JSON.stringify(name);
  return name;
}

function queryValueType(schema) {
  const type = baseType(schema, 'query');
  if (type.endsWith('[]')) return 'ReadonlyArray<string>';
  return type;
}

function requestBodyInfo(operation) {
  const body = operation.requestBody;
  if (!body) return null;
  const schema = body.content?.['application/json']?.schema;
  if (!schema) fail('request body without an application/json schema');
  const name = refName(schema) ?? fail('request body must reference a named component schema');
  usedSchemaNames.add(name);
  return { name, required: body.required !== false };
}

function responseAlias(operation, opId) {
  const response = successResponse(operation);
  if (!response) return `export type ${opId}Response = void;`;
  const schema = responseSchema(response);
  if (!schema) return `export type ${opId}Response = void;`;
  const name = refName(schema);
  if (name) {
    usedSchemaNames.add(name);
    return `export type ${opId}Response = ${name};`;
  }
  if (schema.type === 'array') {
    const itemName = refName(schema.items) ?? fail('array response without a named item schema');
    usedSchemaNames.add(itemName);
    return `export type ${opId}Response = ${itemName}[];`;
  }
  return fail(`unsupported response schema for ${opId}: ${JSON.stringify(schema).slice(0, 120)}`);
}

function emitOperation(pathName, method, operation, pathItemParams) {
  const opId = operation.operationId ?? fail(`operation on ${method.toUpperCase()} ${pathName} has no operationId`);
  const pathParams = pathParamExpressions(operation, pathItemParams);
  const query = queryParameters(operation, pathItemParams);
  const body = requestBodyInfo(operation);

  const args = [];
  for (const param of pathParams) args.push(`${param.tsName}: string`);
  if (body) args.push(body.required ? `request: ${body.name}` : `request?: ${body.name}`);
  if (query.length > 0) args.push(`query?: ${opId}QueryParams`);

  let pathExpression = JSON.stringify(pathName);
  if (pathParams.length > 0) {
    let template = pathName;
    for (const param of pathParams) {
      const placeholder = `{${param.name}}`;
      if (!template.includes(placeholder)) fail(`path parameter ${param.name} missing from ${pathName}`);
      template = template.split(placeholder).join(`\${encodeURIComponent(${param.tsName})}`);
    }
    pathExpression = '`' + template + '`';
  }
  if (query.length > 0) pathExpression = `appendQuery(${pathExpression}, query)`;

  const descriptorParts = [`method: '${method.toUpperCase()}'`, `path: ${pathExpression}`];
  if (body) descriptorParts.push('body: request');

  const deprecated = operation.deprecated === true
    ? '/** @deprecated Retained compatibility alias; see the contract document. */\n'
    : '';

  const queryInterface = query.length > 0
    ? `export type ${opId}QueryParams = {
${query
        .map((parameter) => `  ${JSON.stringify(parameter.name)}${parameter.required ? '' : '?'}: ${queryValueType(parameter.schema)};`)
        .sort()
        .join('\n')}
};\n\n`
    : '';

  return `${deprecated}export function ${opId}(${args.join(', ')}): HttpRequest {
  return { ${descriptorParts.join(', ')} };
}\n\n${responseAlias(operation, opId)}\n\n${queryInterface}`;
}

const operationBlocks = [];
for (const pathName of Object.keys(doc.paths ?? {}).sort()) {
  const pathItem = doc.paths[pathName];
  for (const method of httpMethods) {
    const operation = pathItem?.[method];
    if (!operation) continue;
    operationBlocks.push(emitOperation(pathName, method, operation, pathItem.parameters));
  }
}
operationBlocks.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

const operationsTs = `// GENERATED FILE - DO NOT EDIT.
// Generated by scripts/phase4/generate-openapi-client.mjs from the OpenAPI
// contract api/openapi/medicore-v1.yaml (${doc.info?.title ?? 'MediCore Hospital API'} ${doc.info?.version ?? 'v1'}).
// Regenerate with: scripts/phase4/generate-openapi.sh — never hand-edit.
//
// Transport-agnostic by design: every function returns an HttpRequest
// descriptor for the shared frontend fetch boundary to execute (Bearer
// Authorization header, JSON serialization, ApiError mapping, and 401
// expiry handling stay in exactly one place).

import type {
  ${[...usedSchemaNames].sort().join(',\n  ')},
} from './schemas';

/** The HTTP method the contract documents for an operation. */
export type HttpMethod = 'DELETE' | 'GET' | 'POST' | 'PUT';

/** A fully described request: execute it through the shared API boundary. */
export interface HttpRequest {
  method: HttpMethod;
  path: string;
  body?: unknown;
}

type QueryValue = string | number | boolean | undefined;

/** Appends defined query values (URL-encoded) to a base path. */
function appendQuery(base: string, query?: Record<string, QueryValue>): string {
  if (query === undefined) return base;
  const pairs: string[] = [];
  for (const key of Object.keys(query).sort()) {
    const value = query[key];
    if (value !== undefined) pairs.push(\`\${key}=\${encodeURIComponent(String(value))}\`);
  }
  return pairs.length === 0 ? base : \`\${base}?\${pairs.join('&')}\`;
}

${operationBlocks.join('')}`;

// ---------------------------------------------------------------------------
// index.ts
// ---------------------------------------------------------------------------

const indexTs = `// GENERATED FILE - DO NOT EDIT.
// Generated by scripts/phase4/generate-openapi-client.mjs from the OpenAPI
// contract api/openapi/medicore-v1.yaml (${doc.info?.title ?? 'MediCore Hospital API'} ${doc.info?.version ?? 'v1'}).
// Regenerate with: scripts/phase4/generate-openapi.sh — never hand-edit.

export * from './schemas';
export * from './operations';
`;

// ---------------------------------------------------------------------------
// Emit
// ---------------------------------------------------------------------------

mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'schemas.ts'), schemasTs);
writeFileSync(join(outDir, 'operations.ts'), operationsTs);
writeFileSync(join(outDir, 'index.ts'), indexTs);

console.log(`generate-openapi-client: wrote schemas.ts, operations.ts, index.ts into ${dirname(join(outDir, 'index.ts'))}`);
console.log(`generate-openapi-client: ${schemaNames.length} schemas, ${operationBlocks.length} operations`);
