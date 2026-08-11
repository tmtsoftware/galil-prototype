/*
 * HCD registry — the Galil motion HCDs the UI can inspect, plus the assembly→HCD
 * bindings (SDD Table 6-1). Bindings are DERIVED from each assembly model's
 * *_HCD_PREFIX_STR so the mapping stays code-truth (edit the model, not a table).
 *
 * Each Galil HCD hosts its OWN engineering HMI web console on port 9090 + id
 * (galil-hcd HmiServer; the id is the controller/instance number, i.e. the last
 * segment of the prefix). The HCD panel links to that console — it is a direct,
 * unauthenticated per-controller console that bypasses the gateway/AAS, so we
 * link to it (new tab) rather than embedding it.
 */
import { ComponentId, Prefix } from '@tmtsoftware/esw-ts'
import { IS_PREFIX_STR, IS_HCD_PREFIX_STR } from '../models/insertionStage'
import { SBS_PREFIX_STR, SBS_HCD_PREFIX_STR } from '../models/steeringBeamSplitter'
import { CU_PREFIX_STR, CU_HCD_PREFIX_STR } from '../models/collimatorUnit'
import { CSS_PREFIX_STR, CSS_HCD_PREFIX_STR } from '../models/calibrationSourceStage'
import { PSHFS_PREFIX_STR, PSHFS_HCD_PREFIX_STR } from '../models/pshFocusStage'
import { PSHFW_PREFIX_STR, PSHFW_HCD_PREFIX_STR } from '../models/pshFilterWheel'
import { PSHPMW_PREFIX_STR, PSHPMW_HCD_PREFIX_STR } from '../models/pshPupilMaskWheel'
import { PITFS_PREFIX_STR, PITFS_HCD_PREFIX_STR } from '../models/pitFocusStage'
import { PITFW_PREFIX_STR, PITFW_HCD_PREFIX_STR } from '../models/pitFilterWheel'
import { PITPMW_PREFIX_STR, PITPMW_HCD_PREFIX_STR } from '../models/pitPupilMaskWheel'
import { APTFS_PREFIX_STR, APTFS_HCD_PREFIX_STR } from '../models/aptFocusStage'
import { APTFW_PREFIX_STR, APTFW_HCD_PREFIX_STR } from '../models/aptFilterWheel'
import { TP_PREFIX_STR, TP_HCD_PREFIX_STR } from '../models/tiltPlate'
import { KM_PREFIX_STR, KM_HCD_PREFIX_STR } from '../models/focKMirror'
import { FSS_PREFIX_STR, FSS_HCD_PREFIX_STR } from '../models/fiberSourceStage'
import { PMS_PREFIX_STR, PMS_HCD_PREFIX_STR } from '../models/pupilMaskStage'

type Binding = { assemblyKey: string; hcdKey: string }
const BINDINGS: Binding[] = [
  { assemblyKey: IS_PREFIX_STR, hcdKey: IS_HCD_PREFIX_STR },
  { assemblyKey: SBS_PREFIX_STR, hcdKey: SBS_HCD_PREFIX_STR },
  { assemblyKey: CU_PREFIX_STR, hcdKey: CU_HCD_PREFIX_STR },
  { assemblyKey: CSS_PREFIX_STR, hcdKey: CSS_HCD_PREFIX_STR },
  { assemblyKey: PSHFS_PREFIX_STR, hcdKey: PSHFS_HCD_PREFIX_STR },
  { assemblyKey: PSHFW_PREFIX_STR, hcdKey: PSHFW_HCD_PREFIX_STR },
  { assemblyKey: PSHPMW_PREFIX_STR, hcdKey: PSHPMW_HCD_PREFIX_STR },
  { assemblyKey: PITFS_PREFIX_STR, hcdKey: PITFS_HCD_PREFIX_STR },
  { assemblyKey: PITFW_PREFIX_STR, hcdKey: PITFW_HCD_PREFIX_STR },
  { assemblyKey: PITPMW_PREFIX_STR, hcdKey: PITPMW_HCD_PREFIX_STR },
  { assemblyKey: APTFS_PREFIX_STR, hcdKey: APTFS_HCD_PREFIX_STR },
  { assemblyKey: APTFW_PREFIX_STR, hcdKey: APTFW_HCD_PREFIX_STR },
  { assemblyKey: TP_PREFIX_STR, hcdKey: TP_HCD_PREFIX_STR },
  { assemblyKey: KM_PREFIX_STR, hcdKey: KM_HCD_PREFIX_STR },
  { assemblyKey: FSS_PREFIX_STR, hcdKey: FSS_HCD_PREFIX_STR },
  { assemblyKey: PMS_PREFIX_STR, hcdKey: PMS_HCD_PREFIX_STR }
]

export type HcdDescriptor = {
  key: string // = HCD prefix string (the selector node key)
  label: string
  id: number // controller/instance id; HMI port = 9090 + id
  prefix: Prefix
  componentId: ComponentId
  hmiPort: number
  assemblyKeys: string[] // registry keys of the assemblies bound to this HCD
}

const idOf = (key: string): number => Number(key.slice(key.lastIndexOf('.') + 1))

// Distinct HCD keys, in controller-id order.
const HCD_KEY_LIST = Array.from(new Set(BINDINGS.map((b) => b.hcdKey))).sort((a, b) => idOf(a) - idOf(b))

export const HCDS: Record<string, HcdDescriptor> = Object.fromEntries(
  HCD_KEY_LIST.map((key) => {
    const id = idOf(key)
    const prefix = Prefix.fromString(key)
    return [
      key,
      {
        key,
        label: `Galil HCD ${id}`,
        id,
        prefix,
        componentId: new ComponentId(prefix, 'HCD'),
        hmiPort: 9090 + id,
        assemblyKeys: BINDINGS.filter((b) => b.hcdKey === key).map((b) => b.assemblyKey)
      }
    ]
  })
)

export const HCD_LIST: HcdDescriptor[] = Object.values(HCDS)

export const isHcd = (key: string): boolean => key in HCDS
