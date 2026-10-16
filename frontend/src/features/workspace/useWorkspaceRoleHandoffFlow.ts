import { useState } from 'react'
import type { WorkspaceScope } from './api'
import {
  useAcceptRoleHandoffMutation,
  useCancelRoleHandoffMutation,
  useTransferRoleHandoffMutation,
  type RoleHandoffTransitionCommand,
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

type RoleHandoffTransitionMutation<TRequest> = {
  mutateAsync: (
    command: RoleHandoffTransitionCommand<TRequest>,
    options: { onSuccess: () => void },
  ) => Promise<unknown>
}

type WorkspaceRoleHandoffFlowOptions = {
  scope: WorkspaceScope
  roles: Role[]
  roleHandoffs: RoleHandoff[]
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
      notify('다음 담당자와 기간을 정하고 역할 인수인계 준비를 시작했습니다.')
    })
  }

  const runTransition = <TRequest>(
    mode: Exclude<RoleHandoffModalMode, 'prepare'>,
    mutation: RoleHandoffTransitionMutation<TRequest>,
    request: TRequest,
    successMessage: string,
  ) => {
    if (!actionRole || !actionTarget || action?.mode !== mode) return false
    return mutation.mutateAsync({
      roleId: actionRole.id,
      handoffId: actionTarget.id,
      request,
    }, {
      onSuccess: () => {
        close()
        notify(successMessage)
      },
    })
  }

  const transfer = (request: TransferRoleHandoffRequest) => runTransition(
    'transfer', transferMutation, request, '인수인계를 전달했습니다. 다음 담당자의 수락을 기다립니다.',
  )
  const accept = (request: ConfirmRoleHandoffRequest) => runTransition(
    'accept', acceptMutation, request, '인수인계를 수락해 다음 담당자로 교체했습니다.',
  )
  const cancel = (request: CancelRoleHandoffRequest) => runTransition(
    'cancel', cancelMutation, request, '인수인계를 취소했습니다. 다시 수정할 수 있습니다.',
  )

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
    // 저장소 확인은 역할 인수인계 모달을 그릴 때만 한다.
    hasPendingPreparation: () => preparing && prepareCommand.hasPending(),
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
