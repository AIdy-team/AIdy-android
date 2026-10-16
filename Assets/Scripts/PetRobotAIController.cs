using System.Collections;
using UnityEngine;

/// <summary>
/// AIペットロボットに「自我（自律行動）」を持たせ、
/// 待機、歩行、見回し、タップ時のリアクションなどを自動で行わせるAIコントローラー
/// </summary>
public class PetRobotAIController : MonoBehaviour
{
    public enum AIState
    {
        Idle,           // 待機
        Wander,         // 散歩・トコトコ歩行
        LookAround,     // あたりを見回す
        HappyHop        // 嬉しくて跳ねる
    }

    [Header("AI 行動設定")]
    [SerializeField] private float moveSpeed = 1.2f;
    [SerializeField] private Vector2 walkBoundsX = new Vector2(-1.5f, 1.5f);
    [SerializeField] private Vector2 walkBoundsZ = new Vector2(-0.8f, 0.8f);
    [SerializeField] private float minStateDuration = 2.0f;
    [SerializeField] private float maxStateDuration = 5.0f;

    [Header("アニメーションパーツ参照")]
    [SerializeField] private Transform[] legTransforms; // 足パーツ
    [SerializeField] private Transform bodyTransform;   // 体パーツ

    private AIState currentState = AIState.Idle;
    private Vector3 targetPosition;
    private float stateTimer = 0f;
    private float walkAnimTime = 0f;

    private void Start()
    {
        ChooseNextState();
    }

    private void Update()
    {
        stateTimer -= Time.deltaTime;

        switch (currentState)
        {
            case AIState.Idle:
                // 呼吸のような緩やかな上下運動
                if (bodyTransform != null)
                {
                    float breath = Mathf.Sin(Time.time * 2f) * 0.03f;
                    bodyTransform.localPosition = new Vector3(0, breath, 0);
                }
                break;

            case AIState.Wander:
                MoveToTarget();
                AnimateWalking();
                break;

            case AIState.LookAround:
                // あたりを左右に見回す
                float lookAngle = Mathf.Sin(Time.time * 3f) * 20f;
                transform.localRotation = Quaternion.Euler(0, lookAngle, 0);
                break;

            case AIState.HappyHop:
                // ピョンピョン跳ねる
                float hopY = Mathf.Abs(Mathf.Sin(Time.time * 10f)) * 0.3f;
                if (bodyTransform != null) bodyTransform.localPosition = new Vector3(0, hopY, 0);
                break;
        }

        // タイマー完了で次の行動を選択
        if (stateTimer <= 0f)
        {
            ChooseNextState();
        }
    }

    /// <summary>
    /// 次の自律AI行動を確率で決定します
    /// </summary>
    private void ChooseNextState()
    {
        stateTimer = Random.Range(minStateDuration, maxStateDuration);

        // 状態のランダム選択
        float rand = Random.value;
        if (rand < 0.4f)
        {
            currentState = AIState.Wander;
            targetPosition = new Vector3(
                Random.Range(walkBoundsX.x, walkBoundsX.y),
                transform.position.y,
                Random.Range(walkBoundsZ.x, walkBoundsZ.y)
            );
        }
        else if (rand < 0.7f)
        {
            currentState = AIState.Idle;
        }
        else if (rand < 0.9f)
        {
            currentState = AIState.LookAround;
        }
        else
        {
            currentState = AIState.HappyHop;
        }
    }

    /// <summary>
    /// 目標位置への歩行移動
    /// </summary>
    private void MoveToTarget()
    {
        Vector3 direction = (targetPosition - transform.position).normalized;
        direction.y = 0;

        if (direction != Vector3.zero)
        {
            Quaternion targetRotation = Quaternion.LookRotation(direction);
            transform.rotation = Quaternion.Slerp(transform.rotation, targetRotation, Time.deltaTime * 5f);
        }

        transform.position = Vector3.MoveTowards(transform.position, targetPosition, moveSpeed * Time.deltaTime);

        // 目標到達時にIdleへ移行
        if (Vector3.Distance(transform.position, targetPosition) < 0.1f)
        {
            currentState = AIState.Idle;
        }
    }

    /// <summary>
    /// 歩行時の足スイングアニメーション
    /// </summary>
    private void AnimateWalking()
    {
        walkAnimTime += Time.deltaTime * 10f;
        if (legTransforms != null && legTransforms.Length >= 2)
        {
            for (int i = 0; i < legTransforms.Length; i++)
            {
                float swing = Mathf.Sin(walkAnimTime + (i % 2 == 0 ? 0 : Mathf.PI)) * 15f;
                legTransforms[i].localRotation = Quaternion.Euler(swing, 0, 0);
            }
        }

        // 体のトコトコ上下揺れ
        if (bodyTransform != null)
        {
            float bob = Mathf.Abs(Mathf.Sin(walkAnimTime * 2f)) * 0.05f;
            bodyTransform.localPosition = new Vector3(0, bob, 0);
        }
    }

    /// <summary>
    /// 外部/タップ検知からのリアクション呼び出し
    /// </summary>
    public void OnPetReaction()
    {
        currentState = AIState.HappyHop;
        stateTimer = 2.0f;
    }
}
