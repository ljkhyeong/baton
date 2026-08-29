import { useState } from 'react'
import type { WorkspaceScope } from './api'
import {
  useAcceptRoleHandoffMutation,
  useCancelRoleHandoffMutation,
  useTransferRoleHandoffMutation,
} from './queries'
import { usePrepareRoleHandoffCommand } from './useContentCreationCommand'
import type {
  CancelRoleHandoffRequest,
  ConfirmRoleHandoffRequest,
  PrepareRoleHandoffRequest,
  Role,
  RoleHandoff,
  TransferRoleHandoffRequest,
} from './types'

export type RoleHandoffModalMode = 'prepare' | 'transfer' | 'accept' | 'cancel'

type RoleHandoffAction = {
  mode: RoleHandoffModalMode
  roleId: string
  handoffId?: string
}

type WorkspaceRoleHandoffFlowOptions = {
  scope: WorkspaceScope
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  modalOpen: boolean
  onOpenModal: () => void
  onCloseModal: () => void
  onSelectRole: (roleId: string) => void
  onOpenHandoffView: () => void
  notify: (message: string) => void
}

export function useWorkspaceRoleHandoffFlow({
  scope,
  roles,
  roleHandoffs,
  modalOpen,
  onOpenModal,
  onCloseModal,
  onSelectRole,
  onOpenHandoffView,
  notify,
}: WorkspaceRoleHandoffFlowOptions) {
  const prepareCommand = usePrepareRoleHandoffCommand(scope)
  const transferMutation = useTransferRoleHandoffMutation(scope)
  const acceptMutation = useAcceptRoleHandoffMutation(scope)
  const cancelMutation = useCancelRoleHandoffMutation(scope)
  const [action, setAction] = useState<RoleHandoffAction | null>(null)

  const actionRole = action
    ? roles.find((role) => role.id === action.roleId)
    : undefined
  const actionTarget = action?.handoffId
    ? roleHandoffs.find((handoff) => handoff.id === action.handoffId)
    : undefined
  const activeTransitionMutation = action?.mode === 'transfer'
    ? transferMutation
    : action?.mode === 'accept'
      ? acceptMutation
      : cancelMutation
  const preparing = action?.mode === 'prepare'

  const discard = () => setAction(null)

  const close = () => {
    discard()
    onCloseModal()
  }

  const open = (
    mode: RoleHandoffModalMode,
    role: Role,
    handoff?: RoleHandoff,
  ) => {
    prepareCommand.reset()
    transferMutation.reset()
    acceptMutation.reset()
    cancelMutation.reset()
    setAction({
      mode,
      roleId: role.id,
      handoffId: handoff?.id,
    })
    onOpenModal()
  }

  const prepare = (request: PrepareRoleHandoffRequest) => {
    if (!actionRole || action?.mode !== 'prepare') return false
    return prepareCommand.submit({
      roleId: actionRole.id,
      ...request,
    }, (result) => {
      onSelectRole(result.role.id)
      discard()
      onCloseModal()
      onOpenHandoffView()
      notify('다음 담당자와 기간을 정하고 역할 바통 준비를 시작했어요.')
    })
  }

  const transfer = (request: TransferRoleHandoffRequest) => {
    if (!actionRole || !actionTarget || action?.mode !== 'transfer') return false
    return transferMutation.mutateAsync({
      roleId: actionRole.id,
      handoffId: actionTarget.id,
      request,
    }, {
      onSuccess: () => {
        close()
        notify('바통을 전달했어요. 다음 담당자의 수락을 기다립니다.')
      },
    })
  }

  const accept = (request: ConfirmRoleHandoffRequest) => {
    if (!actionRole || !actionTarget || action?.mode !== 'accept') return false
    return acceptMutation.mutateAsync({
      roleId: actionRole.id,
      handoffId: actionTarget.id,
      request,
    }, {
      onSuccess: () => {
        close()
        notify('다음 담당자의 바통 수락과 역할 배정을 기록했어요.')
      },
    })
  }

  const cancel = (request: CancelRoleHandoffRequest) => {
    if (!actionRole || !actionTarget || action?.mode !== 'cancel') return false
    return cancelMutation.mutateAsync({
      roleId: actionRole.id,
      handoffId: actionTarget.id,
      request,
    }, {
      onSuccess: () => {
        close()
        notify('역할 바통을 취소하고 편집을 다시 열었어요.')
      },
    })
  }

  return {
    action,
    actionRole,
    actionTarget,
    modalPending: preparing
      ? prepareCommand.isPending
      : activeTransitionMutation.isPending,
    modalError: preparing
      ? prepareCommand.error
      : activeTransitionMutation.error,
    modalStorageError: preparing ? prepareCommand.storageError : '',
    recoveryAvailable: Boolean(
      modalOpen && preparing && prepareCommand.hasPending(),
    ),
    transitionPending: prepareCommand.isPending
      || transferMutation.isPending
      || acceptMutation.isPending
      || cancelMutation.isPending,
    open,
    close,
    discard,
    prepare,
    transfer,
    accept,
    cancel,
  }
}
