/*
 * ICS Components selector (SDD §4.3 "Component Selector"). A tree of all
 * ICS/PEAS assemblies, HCDs and sequencers; the user picks one to drive the
 * Status/Command/Configuration area.
 *
 * Enabled nodes are exactly the components present in the registry (their key is
 * the assembly prefix string). Every other node is rendered (to match the SDD
 * layout) but DISABLED — it becomes selectable when that component is added to
 * the registry.
 */
import { Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import React from 'react'
import { isRegistered } from './registry'
import { IS_PREFIX_STR } from '../models/insertionStage'
import { SBS_PREFIX_STR } from '../models/steeringBeamSplitter'
import { CU_PREFIX_STR } from '../models/collimatorUnit'
import { CSS_PREFIX_STR } from '../models/calibrationSourceStage'
import { PSHFS_PREFIX_STR } from '../models/pshFocusStage'
import { PSHFW_PREFIX_STR } from '../models/pshFilterWheel'
import { PITFS_PREFIX_STR } from '../models/pitFocusStage'
import { APTFS_PREFIX_STR } from '../models/aptFocusStage'
import { TP_PREFIX_STR } from '../models/tiltPlate'
import { FSS_PREFIX_STR } from '../models/fiberSourceStage'
import { PMS_PREFIX_STR } from '../models/pupilMaskStage'

// Disabled placeholder node helper.
const ph = (key: string, title: string): DataNode => ({ key, title, disabled: true })

const treeData: DataNode[] = [
  {
    key: 'grp-psh',
    title: 'PSH assemblies',
    selectable: false,
    children: [ph('psh-pmw', 'Pupil Mask Wheel'), { key: PSHFW_PREFIX_STR, title: 'Filter Wheel' }, { key: PSHFS_PREFIX_STR, title: 'Focus Stage' }, ph('psh-det', 'Detector')]
  },
  {
    key: 'grp-pit',
    title: 'PIT assemblies',
    selectable: false,
    children: [ph('pit-pmw', 'Pupil Mask Wheel'), ph('pit-fw', 'Filter Wheel'), { key: PITFS_PREFIX_STR, title: 'Focus Stage' }, ph('pit-det', 'Detector')]
  },
  {
    key: 'grp-apt',
    title: 'APT assemblies',
    selectable: false,
    children: [ph('apt-fw', 'Filter Wheel'), { key: APTFS_PREFIX_STR, title: 'Focus Stage' }, ph('apt-det', 'Detector')]
  },
  { key: 'grp-lowfw', title: 'LOWFW assemblies', disabled: true, children: [ph('lowfw-x', '—')] },
  {
    key: 'grp-foc',
    title: 'FOC assemblies',
    selectable: false,
    children: [
      // Live this session — all share Galil HCD 2 (SDD Fig 2-2).
      { key: SBS_PREFIX_STR, title: 'Steering Beam Splitter' },
      { key: CSS_PREFIX_STR, title: 'Calibration Source Stage' },
      ph('foc-kmirror', 'K-Mirror'),
      { key: CU_PREFIX_STR, title: 'Collimator' },
      { key: TP_PREFIX_STR, title: 'Tilt Plate' }
    ]
  },
  {
    key: 'grp-stim',
    title: 'STIM assemblies',
    selectable: false,
    children: [
      // Live this session.
      { key: IS_PREFIX_STR, title: 'Insertion Stage' },
      { key: FSS_PREFIX_STR, title: 'Fiber Source Stage' },
      { key: PMS_PREFIX_STR, title: 'Pupil Mask Stage' }
    ]
  },
  { key: 'grp-abe', title: 'ABE assemblies', disabled: true, children: [ph('abe-x', '—')] },
  {
    key: 'grp-sw',
    title: 'Software assemblies',
    disabled: true,
    children: [ph('sw-icssama', 'ICS SAMA'), ph('sw-peassama', 'PEAS SAMA'), ph('sw-icscomp', 'ICS Computation'), ph('sw-peascomp', 'PEAS Computation')]
  },
  {
    key: 'grp-hcd',
    title: 'HCDs',
    disabled: true,
    children: [
      ph('hcd-1', 'Galil HCD 1'),
      ph('hcd-2', 'Galil HCD 2'),
      ph('hcd-3', 'Galil HCD 3'),
      ph('hcd-4', 'Galil HCD 4'),
      ph('hcd-teledyne', 'Teledyne Detector HCD'),
      ph('hcd-andor', 'Andor Detector HCD')
    ]
  },
  {
    key: 'grp-seq',
    title: 'Sequencers',
    disabled: true,
    children: [ph('seq-ics', 'ICS Sequencer'), ph('seq-pit', 'PIT Sequencer')]
  }
]

export const ComponentSelector = ({
  selectedKey,
  onSelect
}: {
  selectedKey: string
  onSelect: (key: string) => void
}): React.JSX.Element => (
  <div style={{ padding: '0.5rem 0.25rem' }}>
    <Typography.Text type='secondary' style={{ fontSize: 12, letterSpacing: '0.04em', paddingLeft: 8 }}>
      ICS COMPONENTS
    </Typography.Text>
    <Tree
      treeData={treeData}
      selectedKeys={[selectedKey]}
      defaultExpandedKeys={['grp-stim', 'grp-foc']}
      onSelect={(keys) => {
        const k = keys[0]
        if (typeof k === 'string' && isRegistered(k)) onSelect(k)
      }}
      style={{ background: 'transparent', marginTop: 4 }}
    />
  </div>
)