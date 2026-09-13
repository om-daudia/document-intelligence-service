# Bedrock PDF Extraction Worker

A production-ready Spring Boot application that acts as a Camunda 8 job worker for extracting structured data from PDF documents using Amazon Bedrock (Claude on AWS).

## Overview

The worker picks up Camunda jobs of type `extract-pdf-data`, fetches a PDF from S3, sends it to Claude via the Bedrock Converse API, and returns extracted data as process variables. It uses **prompt caching** to avoid re-processing the static extraction prompt on every document.

## Features

- **Prompt caching**: The ~1500-token extraction prompt is cached after the first call, reducing costs and latency on subsequent documents
- **Virtual threads**: I/O-bound operations run on Java 21 virtual threads for high concurrency without thread pool overhead
- **Concurrency control**: Semaphore-based rate limiting (default 5) prevents overwhelming Bedrock API
- **Structured output**: Tool use with JSON Schema ensures deterministic, valid extraction results
- **Comprehensive validation**: PDF size and page limits enforced before sending to Bedrock
- **Error handling**: Three-way classification—BPMN errors (invalid documents), retryable AWS errors (throttling/timeout), and unexpected failures
- **Observability**: Micrometer counters, structured logging with cache metrics on every call

## Architecture

### Components

1. **PdfExtractionWorker** — Camunda job handler; orchestrates the extraction flow
2. **PdfLoader** — Fetches PDF from S3, validates size and page count
3. **ExtractionService** — Calls Bedrock Converse API with cached prompt; parses tool output
4. **BedrockConfig** — Configures the Bedrock Runtime client with retry policy and timeout
5. **S3Config** — Configures the S3 client

### Data Flow

```
Camunda Job (documentS3Bucket, documentS3Key, documentName)
  ↓
PdfExtractionWorker.extractPdfData()
  ↓
PdfLoader.loadAndValidate() — fetch from S3, check size and pages
  ↓
ExtractionService.extract() — call Bedrock with cached prompt + PDF
  ↓
Parse tool use block → ExtractionResult
  ↓
Complete job with output variables (extractedData, modelId, tokens, cache metrics)
```

## Configuration

All settings are externalized in `application.yml`. Required environment variables:

| Variable | Default | Description |
|---|---|---|
| `BEDROCK_MODEL_ID` | `us.anthropic.claude-3-5-sonnet-20241022-v2:0` | Bedrock model ID with region prefix |
| `AWS_REGION` | `us-east-1` | AWS region (Bedrock and S3) |
| `S3_BUCKET` | *(required)* | S3 bucket holding PDFs |
| `CAMUNDA_CLUSTER_ID` | *(required)* | Camunda 8 cluster ID |
| `CAMUNDA_CLIENT_ID` | *(required)* | Camunda 8 client ID |
| `CAMUNDA_CLIENT_SECRET` | *(required)* | Camunda 8 client secret |

Tuneable parameters in `application.yml`:

```yaml
extraction:
  maxConcurrency: 5              # Semaphore permits
  maxDocumentSizeBytes: 4718592  # 4.5 MB
  maxPages: 100
  requestTimeout: 5m
```

## Running

### Prerequisites

1. **AWS Account** with Bedrock access and an S3 bucket containing test PDFs
2. **Camunda 8 SaaS cluster** with API client credentials
3. **Java 21** (uses virtual threads)
4. **Maven 3.8+**

### Steps

1. **Enable Bedrock model access**:
   - Log into AWS console → Bedrock → Model access
   - Request access to Claude (any 3.x version)
   - Wait for approval (~5 minutes for on-demand)
   - Note the exact model ID (e.g., `us.anthropic.claude-3-5-sonnet-20241022-v2:0`)

2. **Set environment variables**:
   ```bash
   export BEDROCK_MODEL_ID="us.anthropic.claude-3-5-sonnet-20241022-v2:0"
   export AWS_REGION="us-east-1"
   export S3_BUCKET="my-pdf-bucket"
   export CAMUNDA_CLUSTER_ID="..."
   export CAMUNDA_CLIENT_ID="..."
   export CAMUNDA_CLIENT_SECRET="..."
   ```

3. **Build and run**:
   ```bash
   mvn clean spring-boot:run
   ```

4. **Deploy the BPMN** (`bedrock-extraction-service.bpmn`) to your Camunda cluster

5. **Start a process instance** with variables:
   ```json
   {
     "documentS3Bucket": "my-pdf-bucket",
     "documentS3Key": "path/to/document.pdf",
     "documentName": "document.pdf"
   }
   ```

## Verifying Prompt Caching

Prompt caching reduces costs by ~10x after the first document. Check the logs to verify it's working:

### Cold cache (first document):

```
...extraction.ExtractionService : Extraction complete: inputTokens=2543, outputTokens=180, cacheWriteTokens=1952, cacheReadTokens=0
...extraction.ExtractionService : Cache write: 1952 tokens written to cache (cold cache)
```

The `cacheWriteTokens` indicates the prompt is being cached. You are **billed for these tokens**, but they will not be re-billed on subsequent documents.

### Warm cache (second+ document):

```
...extraction.ExtractionService : Extraction complete: inputTokens=642, outputTokens=185, cacheWriteTokens=0, cacheReadTokens=1952
...extraction.ExtractionService : Cache hit: 1952 cache tokens read from previous request
```

The `cacheReadTokens` now matches the previous write, and `cacheWriteTokens` is zero. You are **not billed for the cached portion**; only for the 642 new tokens (the document).

### Troubleshooting

- **`cacheReadTokens` stays at zero** after processing multiple documents:
  - Cache TTL expired (default 5 minutes; traffic must be continuous)
  - Model ID changed between calls (reset cache)
  - Prompt structure changed (e.g., system blocks reordered)
  
- **Model not found**:
  - Verify model ID includes region prefix: `us.`, `eu.`, or `global.`
  - Check AWS Bedrock console for exact model ID
  
- **`ValidationException` with document name**:
  - Filename contains unsupported characters (`_`, `.`, consecutive spaces) — they are sanitized to alphanumeric/`-/()[]`
  - Check logs for original vs. sanitized name

## IAM Permissions

The application's AWS credentials (via `DefaultCredentialsProvider`) need these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock-runtime:InvokeModel",
        "bedrock-runtime:Converse"
      ],
      "Resource": "arn:aws:bedrock:*:*:foundation-model/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::my-pdf-bucket/*"
    }
  ]
}
```

For local development, use `aws configure` to set up credentials or assume a role.

## Observability

### Metrics

Published to `/actuator/metrics` and `/actuator/prometheus`:

- `extraction.documents.processed` — counter
- `extraction.documents.failed` — counter
- `extraction.cache.hits` — counter

### Logging

All extractions are logged at INFO level:

```
Job started: jobKey=2251799813685249, document='contract.pdf'
Starting extraction for document: original='contract.pdf', sanitized='contract.pdf'
Document validated: name='contract.pdf', size=245632 bytes, pages=8
Extraction complete: inputTokens=2543, outputTokens=180, cacheWriteTokens=1952, cacheReadTokens=0
Job completed: jobKey=2251799813685249, document='contract.pdf', extractedFields=[isPersonalTraining, isMonthlyTraining, ...]
```

Errors are logged with context (document name, error code, cause).

## Extraction Result

On success, the job outputs:

| Variable | Type | Content |
|---|---|---|
| `extractedData` | Map | Parsed extraction result (all fields from the JSON schema) |
| `extractionModelId` | String | Model ID used (e.g., `us.anthropic.claude-3-5-sonnet-20241022-v2:0`) |
| `extractionInputTokens` | Integer | Total input tokens (including cached) |
| `extractionOutputTokens` | Integer | Output tokens |
| `extractionCacheReadTokens` | Integer | Tokens read from cache (0 on cold cache) |

The `extractedData` map contains all fields from your schema:
- `isPersonalTraining`, `isMonthlyTraining`, `isAnnualTraining` (booleans)
- `isSignUploaded` (boolean)
- `signPageNumber` (integer array)
- `signatureConfidence` (enum: high/medium/low)
- `signedBy` (string array)
- `detectedAgreements` (array of objects with type, agreementNumber, page, evidence)

## Design Decisions & Scale Considerations

### Prompt Caching Strategy

The extraction prompt (~1500 tokens) is placed in the system block, followed immediately by a cache point. The PDF and per-document instruction go in the user message, after the cache. This ensures:

1. The prompt is cached after the first call
2. The PDF never invalidates the cache (it comes after the cache point)
3. Cost savings are ~10x for documents 2+

**Cache TTL is 5 minutes** by default. If traffic is sparse, the cache expires and you pay the write cost again. For high-volume extraction (e.g., 100+ docs/hour), caching remains active indefinitely.

### Concurrency & Virtual Threads

Java 21 virtual threads allow thousands of concurrent jobs without thread pool overhead. However, Bedrock API has rate limits (~100 requests/sec on-demand). We use a **Semaphore with 5 permits** to throttle outbound calls:

- Incoming jobs queue if 5 are already in-flight
- Virtual threads park efficiently (no context switch cost)
- Adjust `maxConcurrency` in config if Bedrock throttles you

**At scale** (10,000+ jobs/day):
- Increase `maxConcurrency` gradually; monitor Bedrock metrics for throttling
- If hitting 429 (ThrottlingException), the worker automatically retries with exponential backoff
- Consider batching multiple PDFs per Bedrock call if your extraction logic permits (not implemented here)

### Error Handling

Three cases are distinguished:

1. **BPMN Errors** (`EXTRACTION_INVALID_DOCUMENT`, `EXTRACTION_FAILED`):
   - Oversized/too-many-pages PDF
   - PDF is malformed (not a valid PDF)
   - Model returned no tool use block
   - These errors do not retry; the process moves to an error end event

2. **Retryable AWS Errors** (thrown as `ExtractionRetryableException`):
   - `ThrottlingException` (rate limit)
   - Timeouts (>5 minutes)
   - Transient network errors
   - Camunda retries with backoff (configured retries in the BPMN)

3. **Unexpected Errors**:
   - Any other exception is logged and treated as a hard failure for that attempt
   - Retried per the BPMN task definition (default 3 retries)

### Document Name Sanitization

Bedrock only accepts alphanumeric, space, `-`, `()`, `[]` in document names. Any other character (underscore, period, emoji) is stripped. The logs show both original and sanitized names so you can match them back.

## Future Enhancements

For higher scale or multi-tenant scenarios:

1. **Batch processing**: Send multiple PDFs per request (max 5) to amortize request overhead
2. **Prompt versioning**: Store prompts in a database or config service; version them for reproducibility
3. **Result caching**: Cache extraction results by document hash to avoid re-processing identical PDFs
4. **Custom retry logic**: Implement exponential backoff and jitter in the worker instead of relying on Camunda's retry
5. **Secrets management**: Use AWS Secrets Manager for Camunda credentials instead of environment variables
6. **Dead letter queue**: Route permanently failed documents to a DLQ for manual review
7. **Extraction feedback loop**: Log user corrections to refine the extraction prompt over time

## Troubleshooting

### `ThrottlingException` in logs

Bedrock rate limit (on-demand ~100 req/sec). The worker retries automatically. To reduce:
- Lower `maxConcurrency` in config
- Increase time between job submissions
- Request higher quota from AWS

### Large token costs despite caching

- Verify logs show `cacheReadTokens > 0` on second+ call
- Check cache TTL: if gaps > 5 minutes between calls, cache expires
- Inspect BPMN: confirm jobs are serialized (not all parallel)

### `ValidationException: invalid document name`

Document name contains characters Bedrock rejects. Check logs for original vs. sanitized name. (Most common: underscores in filenames.)

### Model access denied

- AWS Bedrock console → Model access → request access to Claude
- May take 5–15 minutes to activate
- Use exact model ID from the console

## License

Apache 2.0
