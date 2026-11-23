import { z } from "zod";

/*
 * Every body schema is `.strict()`: unknown fields are rejected with 400.
 * This is a privacy rule — screen contents can never sneak into storage.
 */

// ---- Plan (mirrors shared/schema/plan.schema.json) ----

const SURFACE_ID =
  /^(instagram\.reels|youtube\.shorts|snapchat\.spotlight|facebook\.reels|app:[A-Za-z0-9._]+|site:[a-z0-9.-]+)$/;
const HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;
const DOMAIN = /^[a-z0-9.-]+\.[a-z]{2,}$/;

export const SurfaceSchema = z
  .object({
    id: z.string().regex(SURFACE_ID),
    mode: z.enum(["hide_section", "block_app"]),
  })
  .strict();

export const ScheduleSchema = z
  .object({
    id: z.string().min(1),
    label: z.string().min(1).max(60),
    days: z
      .array(z.number().int().min(1).max(7))
      .min(1)
      .refine((d) => new Set(d).size === d.length, { message: "days must be unique" }),
    start: z.string().regex(HHMM),
    end: z.string().regex(HHMM),
    strict: z.boolean().optional(),
  })
  .strict();

export const KeyHolderSchema = z
  .object({
    id: z.string(),
    name: z.string().max(60),
    relation: z.enum(["self", "friend", "parent"]),
  })
  .strict();

export const UnlockPolicySchema = z
  .object({
    waitSeconds: z.number().int().min(0).max(600).optional(),
    requireReason: z.boolean().optional(),
    notifyKeyHolder: z.boolean().optional(),
    approvalMinutes: z.number().int().min(1).max(240).optional(),
  })
  .strict();

export const PlanSchema = z
  .object({
    version: z.number().int().min(1),
    distractions: z.array(SurfaceSchema),
    schedules: z.array(ScheduleSchema),
    keyHolder: KeyHolderSchema.nullable().optional(),
    unlockPolicy: UnlockPolicySchema.optional(),
    siteBlocks: z.array(z.string().regex(DOMAIN)).optional(),
  })
  .strict();

export type Plan = z.infer<typeof PlanSchema>;

// ---- Auth ----

export const OtpStartSchema = z
  .object({
    contact: z.string().trim().min(3).max(254),
  })
  .strict();

export const OtpVerifySchema = z
  .object({
    contact: z.string().trim().min(3).max(254),
    code: z.string().regex(/^[0-9]{6}$/),
    /** Optional display name shown to key holders. Additive to the contract. */
    name: z.string().trim().min(1).max(60).optional(),
  })
  .strict();

// ---- Key holder / unlocks ----

export const EmptyBodySchema = z.object({}).strict();

export const UnlockRequestSchema = z
  .object({
    surfaceId: z.string().regex(SURFACE_ID).nullable(),
    reason: z.string().trim().min(1).max(280),
  })
  .strict();

export const DecisionSchema = z
  .object({
    approve: z.boolean(),
    minutes: z.number().int().min(1).max(240).optional(),
  })
  .strict();

// ---- Sessions ----

export const FocusSessionSchema = z
  .object({
    id: z.string().min(1).max(100),
    scheduleId: z.string().min(1).max(100).nullable(),
    start: z.iso.datetime({ offset: true }),
    end: z.iso.datetime({ offset: true }),
    intercepts: z.number().int().min(0),
    unlocks: z.number().int().min(0),
  })
  .strict()
  .refine((s) => Date.parse(s.end) >= Date.parse(s.start), {
    message: "end must not be before start",
    path: ["end"],
  });

export type FocusSession = z.infer<typeof FocusSessionSchema>;

// ---- Billing ----

export const BillingVerifySchema = z
  .object({
    productId: z.enum(["steady_plus_monthly", "steady_plus_yearly"]),
    purchaseToken: z.string().min(1).max(4096),
  })
  .strict();
