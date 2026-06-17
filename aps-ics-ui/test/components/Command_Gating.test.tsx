/*
 * Command-gating smoke test — pure logic (no React, no antd), so it cannot hit
 * the antd-render hang that plagues this runner. Exercises commandEnabled for
 * both assemblies, mirroring StageAssemblyHandlers.validateCommand:
 *   not ready / busy -> all off
 *   FAULTED          -> all off
 *   PROCESSING       -> all off
 *   ERROR_RECOVERY   -> only abortErrorRecovery
 *   PRE_HOMED        -> only configure / home
 *   OPERATIONAL      -> motion enabled
 */
import { expect } from 'chai'
import { commandEnabled as isEnabled } from '../../src/models/insertionStage'
import { commandEnabled as sbsEnabled } from '../../src/models/steeringBeamSplitter'
import { commandEnabled as cuEnabled } from '../../src/models/collimatorUnit'
import { commandEnabled as cssEnabled } from '../../src/models/calibrationSourceStage'

describe('InsertionStage command gating', () => {
  const preHomed = { assemblyState: 'PRE_HOMED', commandState: 'IDLE' }
  const operational = { assemblyState: 'OPERATIONAL', commandState: 'IDLE' }

  it('rejects everything when not ready', () => {
    expect(isEnabled('home', preHomed, false, false)).to.equal(false)
  })

  it('rejects everything while busy', () => {
    expect(isEnabled('home', preHomed, true, true)).to.equal(false)
  })

  it('PRE_HOMED allows only configure/home', () => {
    expect(isEnabled('home', preHomed, true, false)).to.equal(true)
    expect(isEnabled('configure', preHomed, true, false)).to.equal(true)
    expect(isEnabled('positionStage', preHomed, true, false)).to.equal(false)
    expect(isEnabled('moveToDefaultPosition', preHomed, true, false)).to.equal(false)
  })

  it('OPERATIONAL enables motion', () => {
    expect(isEnabled('positionStage', operational, true, false)).to.equal(true)
    expect(isEnabled('selectSource', operational, true, false)).to.equal(true)
    expect(isEnabled('stop', operational, true, false)).to.equal(true)
  })

  it('FAULTED rejects all', () => {
    expect(isEnabled('home', { assemblyState: 'FAULTED' }, true, false)).to.equal(false)
    expect(isEnabled('abortErrorRecovery', { assemblyState: 'FAULTED' }, true, false)).to.equal(false)
  })

  it('ERROR_RECOVERY allows only abortErrorRecovery', () => {
    const s = { assemblyState: 'OPERATIONAL', commandState: 'ERROR_RECOVERY' }
    expect(isEnabled('abortErrorRecovery', s, true, false)).to.equal(true)
    expect(isEnabled('positionStage', s, true, false)).to.equal(false)
    expect(isEnabled('home', s, true, false)).to.equal(false)
  })

  it('PROCESSING rejects all', () => {
    const s = { assemblyState: 'OPERATIONAL', commandState: 'PROCESSING' }
    expect(isEnabled('positionStage', s, true, false)).to.equal(false)
    expect(isEnabled('stop', s, true, false)).to.equal(false)
  })
})

describe('SteeringBeamSplitter command gating', () => {
  const operational = { assemblyState: 'OPERATIONAL', commandState: 'IDLE' }
  const preHomed = { assemblyState: 'PRE_HOMED', commandState: 'IDLE' }

  it('PRE_HOMED allows only configure/home', () => {
    expect(sbsEnabled('home', preHomed, true, false)).to.equal(true)
    expect(sbsEnabled('positionBeamSplitter', preHomed, true, false)).to.equal(false)
  })

  it('OPERATIONAL enables positionBeamSplitter', () => {
    expect(sbsEnabled('positionBeamSplitter', operational, true, false)).to.equal(true)
  })

  it('ERROR_RECOVERY allows only abortErrorRecovery', () => {
    const s = { assemblyState: 'OPERATIONAL', commandState: 'ERROR_RECOVERY' }
    expect(sbsEnabled('abortErrorRecovery', s, true, false)).to.equal(true)
    expect(sbsEnabled('positionBeamSplitter', s, true, false)).to.equal(false)
  })
})
describe('CollimatorUnit command gating', () => {
  const operational = { assemblyState: 'OPERATIONAL', commandState: 'IDLE' }
  const preHomed = { assemblyState: 'PRE_HOMED', commandState: 'IDLE' }

  it('PRE_HOMED allows only configure/home', () => {
    expect(cuEnabled('home', preHomed, true, false)).to.equal(true)
    expect(cuEnabled('configure', preHomed, true, false)).to.equal(true)
    expect(cuEnabled('changeScale', preHomed, true, false)).to.equal(false)
    expect(cuEnabled('positionFrontAxis', preHomed, true, false)).to.equal(false)
  })

  it('OPERATIONAL enables motion commands', () => {
    expect(cuEnabled('changeScale', operational, true, false)).to.equal(true)
    expect(cuEnabled('positionFrontAxis', operational, true, false)).to.equal(true)
    expect(cuEnabled('positionRearAxis', operational, true, false)).to.equal(true)
  })

  it('ERROR_RECOVERY allows only abortErrorRecovery', () => {
    const s = { assemblyState: 'OPERATIONAL', commandState: 'ERROR_RECOVERY' }
    expect(cuEnabled('abortErrorRecovery', s, true, false)).to.equal(true)
    expect(cuEnabled('changeScale', s, true, false)).to.equal(false)
  })
})

describe('CalibrationSourceStage command gating', () => {
  const operational = { assemblyState: 'OPERATIONAL', commandState: 'IDLE' }
  const preHomed = { assemblyState: 'PRE_HOMED', commandState: 'IDLE' }

  it('PRE_HOMED allows only configure/home', () => {
    expect(cssEnabled('home', preHomed, true, false)).to.equal(true)
    expect(cssEnabled('setOptic', preHomed, true, false)).to.equal(false)
    expect(cssEnabled('setSourceIntensity', preHomed, true, false)).to.equal(false)
  })

  it('OPERATIONAL enables optic/slot/position and light commands', () => {
    expect(cssEnabled('setOptic', operational, true, false)).to.equal(true)
    expect(cssEnabled('setSlot', operational, true, false)).to.equal(true)
    expect(cssEnabled('setPosition', operational, true, false)).to.equal(true)
    expect(cssEnabled('setOpticAndSourceIntensity', operational, true, false)).to.equal(true)
    expect(cssEnabled('setSourceIntensity', operational, true, false)).to.equal(true)
  })

  it('ERROR_RECOVERY allows only abortErrorRecovery', () => {
    const s = { assemblyState: 'OPERATIONAL', commandState: 'ERROR_RECOVERY' }
    expect(cssEnabled('abortErrorRecovery', s, true, false)).to.equal(true)
    expect(cssEnabled('setOptic', s, true, false)).to.equal(false)
  })
})
