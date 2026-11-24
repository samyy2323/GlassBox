# Testing Glassbox with the Harbor Logistics handbook

The app is at http://localhost:8080. The database was just reset, so sign up first (any e-mail, a password of at least 8 characters).

## 1. Upload and chat

1. Documents page: upload `Harbor-Logistics-Handbook.md` (edition 1). Wait for the status to become READY.
2. Chat page: ask these. The answer should cite the handbook, and the citation should highlight the matching sentence.

| Question | Expected answer |
|---|---|
| How many days of annual leave does a full-time employee get? | 20 days (plus 9 public holidays) |
| How many unused leave days can be carried over, and by when must they be used? | 5 days, by 31 March |
| What is the maximum forklift speed in the container yard? | 15 km/h (8 km/h inside the warehouse) |
| How long is a forklift licence valid? | 3 years |
| Within how many hours must the written incident form be submitted? | 24 hours |
| What is the daily meal allowance for business travel? | 45 euros |
| Above what amount does an expense need a receipt and department head approval? | 75 euros |
| How many days a week may office staff work from home? | Up to 2 |
| How often must passwords be changed, and how long must they be? | Every 180 days, at least 14 characters |
| What is the extension of the IT helpdesk? | 4417 |
| Who is the safety officer at Eastgate? | Lena Vogel |
| When does the company doctor hold a clinic? | Tuesday 10:00 to 12:00 at Port Merrow |

Questions that need two facts (a good test of retrieval):

| Question | Expected answer |
|---|---|
| Who should I phone if I am sick, and by what time for the early shift? | The shift supervisor, no later than 05:30 |
| How is overtime paid? | 1.5 times for the first 10 overtime hours a week, 2 times after that |
| Compare sick pay in the first 10 days with the following 20 days. | Full pay, then 60 percent |

**Questions the handbook cannot answer.** The right answer is "The answer is not in the documents.":

- What is the company pension contribution?
- Who is the CEO of Harbor Logistics?
- How many days of parental leave are there? (only true for edition 1; edition 2 adds it)

Click **X-Ray** on an answer to see the stages: query embedding, retrieval candidates, the context that reached the prompt, the citations.

## 2. Thumbs down

Ask "What is the daily meal allowance?", click the thumbs-down, then open the Feedback page. Mark the passage that holds the right answer, pick a test set and save: the question becomes a test question that came from a real failure.

## 3. Test set

Test sets page: generate a set of about 20 questions from the handbook (the local model needs roughly 15 seconds per question), review them with the keyboard, accept the good ones, finalize.

## 4. Experiment (Eval Lab)

Experiments page: pick the final test set and compare vector, keyword and hybrid search. Look at the confidence intervals and the verdict text. Open a failed question to see which stage lost it. Use **Share** for a PDF report or a read-only link, and open the link in a private window.

## 5. Replace a document (change impact)

Documents page: replace the handbook with `Harbor-Logistics-Handbook-v2.md`. When ingestion finishes, open the document's **Changes** page. You should see:

- Annual leave 20 -> 24 days and peak season 2 -> 3 people per shift
- incident form 24 -> 12 hours
- meal allowance 45 -> 50 euros and the receipt limit 75 -> 100 euros
- remote work 2 -> 3 days
- a new section 11 on parental leave

The questions about those facts are marked stale (their evidence changed) and the earlier measurement is re-run on the new text. Now ask the leave question in Chat again: the answer should say 24.

## 6. Budget and AutoTune

Budget page: set a monthly budget and a per-run cap, then try to start an experiment that is estimated above it. It should be refused with the numbers. AutoTune page: start a small search on the final test set (it only needs retrieval, no model calls).

## 7. Try to break it

Upload `Hostile-Notice.md`, then ask "What is the cafeteria opening time?". The planted instruction must not turn the answer into a link or make it follow the document.
